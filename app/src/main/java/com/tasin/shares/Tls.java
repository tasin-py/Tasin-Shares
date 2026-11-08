package com.tasin.shares;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Calendar;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * HTTPS for the page's secure copy. Browsers give a page the phone's motion sensors (the gyro mouse) only over
 * HTTPS. The TV makes its own certificate once; the key stays in Android's keystore and never leaves the TV.
 * The certificate is self-signed, so a browser warns once before it opens the page.
 */
final class Tls {
    private static final String ALIAS = "tasin-shares-https";

    private Tls() {
    }

    static SSLServerSocketFactory serverFactory() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar from = Calendar.getInstance();
            from.add(Calendar.DAY_OF_YEAR, -1);
            Calendar to = Calendar.getInstance();
            to.add(Calendar.YEAR, 20);
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
                            KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(new X500Principal("CN=Tasin Shares"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(from.getTime())
                    .setCertificateNotAfter(to.getTime())
                    .build());
            g.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        X509Certificate[] chain = {cert};

        X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return ALIAS;
            }

            @Override
            public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                return ALIAS;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return chain;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return null;
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{km}, null, null);
        return ctx.getServerSocketFactory();
    }
}
