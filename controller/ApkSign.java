import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public final class ApkSign {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "keystore storepass alias keypass input.apk output.apk");
        }
        KeyStore store = KeyStore.getInstance("JKS");
        try (FileInputStream input = new FileInputStream(args[0])) {
            store.load(input, args[1].toCharArray());
        }
        PrivateKey key = (PrivateKey) store.getKey(args[2], args[3].toCharArray());
        X509Certificate certificate = (X509Certificate) store.getCertificate(args[2]);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                args[2], key, Collections.singletonList(certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(args[4]))
                .setOutputApk(new File(args[5]))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[5])).build().verify();
        if (!result.isVerified()) {
            throw new IllegalStateException("APK verification failed: " + result.getErrors());
        }
        System.out.println("verified=" + result.isVerified()
                + " v1=" + result.isVerifiedUsingV1Scheme()
                + " v2=" + result.isVerifiedUsingV2Scheme()
                + " v3=" + result.isVerifiedUsingV3Scheme());
    }
}
