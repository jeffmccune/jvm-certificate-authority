package puppetlabs.jvm.certificate_authority;

import puppetlabs.jvm.PathUtils;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.openssl.PEMReader;
import org.joda.time.DateTime;
import org.joda.time.Period;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

public class PuppetMasterCertManager {
    private static final String PATH_CA_PUBLIC_KEY      = "ca/ca_pub.pem";
    private static final String PATH_CA_PRIVATE_KEY     = "ca/ca_key.pem";
    private static final String PATH_CA_CERT            = "ca/ca_crt.pem";
    private static final String PATH_CA_CRL             = "ca/ca_crl.pem";
    private static final String PATH_HOST_PUBLIC_KEYS   = "public_keys";
    private static final String PATH_HOST_PRIVATE_KEYS  = "private_keys";
    private static final String PATH_HOST_CERTS         = "certs";

    private static final AtomicInteger nextSerialNum = new AtomicInteger(1);

    private final String masterCertname;
    private final String sslDir;
    private final X500Name caX500Name;

    private final String caPublicKeyPath;
    private final String caPrivateKeyPath;
    private final String caCertPath;
    private final String caCrlPath;
    private final PrivateKey caPrivateKey;

    private final String masterPublicKeyPath;
    private final String masterPrivateKeyPath;
    private final String masterCertPath;

    // TODO: the exception handling in this class is terrible; should be catching
    //  most of these and raising a more general PuppetCert exception
    //  or similar

    public PuppetMasterCertManager(String confDir, String masterCertname)
            throws IOException, NoSuchProviderException, NoSuchAlgorithmException, CRLException, OperatorCreationException, CertificateException, KeyStoreException {
        this.sslDir = PathUtils.concat(confDir, "ssl");
        this.masterCertname = masterCertname;

        this.caX500Name = CertificateUtils.generateX500Name("Puppet CA: " + masterCertname);

        this.caPublicKeyPath  = PathUtils.concat(sslDir, PATH_CA_PUBLIC_KEY);
        this.caPrivateKeyPath = PathUtils.concat(sslDir, PATH_CA_PRIVATE_KEY);
        this.caCertPath       = PathUtils.concat(sslDir, PATH_CA_CERT);
        this.caCrlPath        = PathUtils.concat(sslDir, PATH_CA_CRL);

        this.masterPublicKeyPath  = PathUtils.concat(sslDir, PATH_HOST_PUBLIC_KEYS, this.masterCertname + ".pem");
        this.masterPrivateKeyPath = PathUtils.concat(sslDir, PATH_HOST_PRIVATE_KEYS, this.masterCertname + ".pem");
        this.masterCertPath       = getHostCertPath(this.masterCertname.toString());

        initializeCACert();

        this.caPrivateKey = readPrivateKey(new FileReader(this.caPrivateKeyPath));

        initializeMasterCert();

        // Save pems to keystore
        KeyStore ks = CertificateUtils.createKeyStore();
        String keystorePassword = "puppet";
        X509Certificate caCert = CertificateUtils.pemToCerts(new FileReader(this.caCertPath)).get(0);
        X509Certificate hostCert = CertificateUtils.pemToCerts(new FileReader(this.masterCertPath)).get(0);
        PrivateKey hostPrivateKey = CertificateUtils.pemToPrivateKeys(new FileReader(this.masterPrivateKeyPath)).get(0);
        CertificateUtils.associateCert(ks, "ca-cert-alias", caCert);
        CertificateUtils.associateCert(ks, "ca-cert-alias", hostCert);
        CertificateUtils.associatePrivateKey(ks, "key-alias", hostPrivateKey, keystorePassword, hostCert);
    }

    private static PrivateKey readPrivateKey(Reader keyReader) throws IOException {
        PEMReader reader = new PEMReader(keyReader);
        return ((KeyPair) reader.readObject()).getPrivate();
    }

    public InputStream getCertStream(String certname) throws FileNotFoundException {
        String certFilePath = getHostCertPath(certname);
        if (new File(certFilePath).exists()) {
            return new FileInputStream(certFilePath);
        }
        return null;
    }

    public X509Certificate signCertificateRequest(String certname, PKCS10CertificationRequest certRequest) throws IOException, OperatorCreationException, CertificateException {
        // TODO: we are just autosigning here, never saving the CSR to disk.
        X509Certificate cert = CertificateUtils.signCertificateRequest(certRequest, caX500Name, nextSerial(), caPrivateKey);
        CertificateUtils.writeToPEM(cert, new FileWriter(getHostCertPath(certname)));
        return cert;
    }

    public String signCertificateRequestStream(String certname, InputStream certRequestStream) throws IOException, OperatorCreationException, CertificateException {
        PKCS10CertificationRequest certReq = readCertificateRequest(new InputStreamReader(certRequestStream));
        signCertificateRequest(certname, certReq);

        // Yuck.  Unfortunately this marshalled ruby object is what the agent
        //  expects to receive.
        return "--- \n  - !ruby/object:Puppet::SSL::CertificateRequest\n    name: " +
                certname + "\n    content: !ruby/object:OpenSSL::X509::Request {}\n    expiration: " +
                // TODO: pull the *real* expiration date off of the cert req
                DateTime.now().plus(Period.years(5)).toString();
    }

    private static PKCS10CertificationRequest readCertificateRequest(Reader certReqReader) throws IOException {
        PEMReader reader = new PEMReader(certReqReader);
        return new PKCS10CertificationRequest(((PKCS10CertificationRequest) reader.readObject()).getEncoded());
    }

    public InputStream getCRLStream() throws FileNotFoundException {
        return new FileInputStream(PathUtils.concat(sslDir, PATH_CA_CRL));
    }

    private void initializeCACert() throws NoSuchProviderException, NoSuchAlgorithmException, IOException, OperatorCreationException, CRLException, CertificateException {

        if (new File(this.caPublicKeyPath).exists() &&
            new File(this.caPrivateKeyPath).exists() &&
            new File(this.caCertPath).exists() &&
            new File(this.caCrlPath).exists()) {
            return;
        }

        for (String filePath : new String[] {
                this.caPublicKeyPath, this.caPrivateKeyPath,
                this.caCertPath, this.caCrlPath }) {
            FileUtils.forceMkdir(new File(filePath).getParentFile());
        }

        KeyPair caKeyPair = CertificateUtils.generateKeyPair();
        CertificateUtils.writeToPEM(caKeyPair.getPublic(), new FileWriter(this.caPublicKeyPath));
        CertificateUtils.writeToPEM(caKeyPair.getPrivate(), new FileWriter(this.caPrivateKeyPath));

        PKCS10CertificationRequest caCertReq = CertificateUtils.generateCertReq(caKeyPair, this.caX500Name);
        X509Certificate caCert = CertificateUtils.signCertificateRequest(caCertReq, this.caX500Name, nextSerial(), caKeyPair.getPrivate());
        CertificateUtils.writeToPEM(caCert, new FileWriter(this.caCertPath));

        FileUtils.copyFile(new File(this.caCertPath), new File(getHostCertPath("ca")));

        X509CRL caCrl = CertificateUtils.generateCRL(caCert.getIssuerX500Principal(), caKeyPair.getPrivate());
        CertificateUtils.writeToPEM(caCrl, new FileWriter(this.caCrlPath));
    }

    private String getHostCertPath(String hostCertName) {
        return PathUtils.concat(this.sslDir, PATH_HOST_CERTS, hostCertName + ".pem");
    }

    private void initializeMasterCert() throws IOException, NoSuchProviderException, NoSuchAlgorithmException, OperatorCreationException, CertificateException {

        if (new File(masterPublicKeyPath).exists() &&
            new File(masterPrivateKeyPath).exists() &&
            new File(masterCertPath).exists()) {
            return;
        }

        for (String filePath : new String[] {
                this.masterPublicKeyPath, this.masterPrivateKeyPath,
                this.masterCertPath }) {
            FileUtils.forceMkdir(new File(filePath).getParentFile());
        }

        KeyPair masterKeyPair = CertificateUtils.generateKeyPair();
        CertificateUtils.writeToPEM(masterKeyPair.getPublic(), new FileWriter(masterPublicKeyPath));
        CertificateUtils.writeToPEM(masterKeyPair.getPrivate(), new FileWriter(masterPrivateKeyPath));

        X500Name masterX500Name = CertificateUtils.generateX500Name(masterCertname);

        PKCS10CertificationRequest masterCertReq = CertificateUtils.generateCertReq(masterKeyPair, masterX500Name);
        X509Certificate caCert = CertificateUtils.signCertificateRequest(masterCertReq, this.caX500Name, nextSerial(), caPrivateKey);
        CertificateUtils.writeToPEM(caCert, new FileWriter(masterCertPath));
    }

    private static BigInteger nextSerial() {
        // TODO: this needs to be able to persist between runs.
        int val = nextSerialNum.getAndIncrement();
        return BigInteger.valueOf(val);
    }
}
