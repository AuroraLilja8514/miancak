// SPDX-License-Identifier: GPL-3.0-only
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;

public class KeyStoreInterop {
  public static void main(String[] args) throws Exception {
    char[] password = System.getenv("MIANCAK_FIXTURE_PASSWORD").toCharArray();
    try {
      Path directory = Path.of(System.getenv("MIANCAK_FIXTURE_DIRECTORY"));
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream input = Files.newInputStream(directory.resolve("miancak-release.p12"))) { store.load(input, password); }
      if (store.size() != 1 || !store.isKeyEntry("miancak-release")) throw new AssertionError("Alias mismatch");
      PrivateKey key = (PrivateKey) store.getKey("miancak-release", password);
      if (((RSAPrivateKey) key).getModulus().bitLength() != 3072) throw new AssertionError("RSA size mismatch");
      X509Certificate certificate = (X509Certificate) store.getCertificate("miancak-release");
      certificate.verify(certificate.getPublicKey());
      certificate.checkValidity();
      if (!certificate.getSigAlgName().equalsIgnoreCase("SHA256withRSA")) throw new AssertionError("Signature mismatch");
      X509Certificate pem;
      try (InputStream input = Files.newInputStream(directory.resolve("miancak-release-cert.pem"))) {
        pem = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
      if (!Arrays.equals(certificate.getEncoded(), pem.getEncoded())) throw new AssertionError("Public certificate mismatch");
      Signature signer = Signature.getInstance("SHA256withRSA");
      byte[] probe = "MIANCAK Java interop verification".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      signer.initSign(key); signer.update(probe); byte[] signed = signer.sign();
      signer.initVerify(certificate); signer.update(probe);
      if (!signer.verify(signed)) throw new AssertionError("Java signing failed");
      System.out.println("JAVA17_PKCS12_ALIAS_CERTIFICATE_SIGNING_PASS");
    } finally { Arrays.fill(password, '\0'); }
  }
}
