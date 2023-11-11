/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

public class GitHubAppCredentials {
    private static final String PKCS1_KEY_START = "-----BEGIN RSA PRIVATE KEY-----\n";
    private static final String PKCS1_KEY_END = "-----END RSA PRIVATE KEY-----";
    private static final String PKCS8_KEY_START = "-----BEGIN PRIVATE KEY-----\n";
    private static final String PKCS8_KEY_END = "-----END PRIVATE KEY-----";

    public static PrivateKey parsePKCS8(String input) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        final byte[] key;
        if (input.startsWith(PKCS8_KEY_START)) {
            input = input.replace(PKCS8_KEY_START, "").replace(PKCS8_KEY_END, "").replaceAll("\\s", "");
            key = Base64.getDecoder().decode(input);
        } else {
            input = input.replace(PKCS1_KEY_START, "").replace(PKCS1_KEY_END, "").replaceAll("\\s", "");
            byte[] pkcs1Encoded = Base64.getDecoder().decode(input);
            AlgorithmIdentifier algId = new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);
            PrivateKeyInfo privateKeyInfo = new PrivateKeyInfo(algId, ASN1Sequence.getInstance(pkcs1Encoded));
            key = privateKeyInfo.getEncoded();
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
    }

    public static CredentialsProvider jwt(String appId, PrivateKey privateKey, TokenGetter tokenGetter) {
        return new CredentialsProvider() {
            @Override
            public boolean isInteractive() {
                return false;
            }

            @Override
            public boolean supports(CredentialItem... items) {
                for (CredentialItem i : items) {
                    if (i instanceof CredentialItem.InformationalMessage) {
                        continue;
                    }
                    if (i instanceof CredentialItem.Username) {
                        continue;
                    }
                    if (i instanceof CredentialItem.Password) {
                        continue;
                    }
                    if (i instanceof CredentialItem.StringType) {
                        if (i.getPromptText().equals("Password: ")) { //$NON-NLS-1$
                            continue;
                        }
                    }
                    return false;
                }
                return true;
            }

            @Override
            public boolean get(URIish uri, CredentialItem... items)
                    throws UnsupportedCredentialItem {
                try {
                    for (CredentialItem i : items) {
                        if (i instanceof CredentialItem.InformationalMessage) {
                            continue;
                        }
                        if (i instanceof CredentialItem.Username) {
                            ((CredentialItem.Username) i).setValue(jwt());
                            continue;
                        }
                        if (i instanceof CredentialItem.Password) {
                            ((CredentialItem.Password) i).setValue(jwt().toCharArray());
                            continue;
                        }
                        if (i instanceof CredentialItem.StringType) {
                            if (i.getPromptText().equals("Password: ")) { //$NON-NLS-1$
                                ((CredentialItem.StringType) i).setValue(new String(jwt()));
                                continue;
                            }
                        }
                        throw new UnsupportedCredentialItem(uri, i.getClass().getName()
                                + ":" + i.getPromptText()); //$NON-NLS-1$
                    }
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
                return true;
            }

            private Jwt jwt = null;
            public String jwt() throws IOException {
                final Instant now = Instant.now();
                if (jwt == null) {
                    this.jwt = newJwt();
                } else if (now.isAfter(jwt.expirationDate())) {
                    this.jwt = newJwt();
                }
                return jwt.jwt();
            }

            public Jwt newJwt() throws IOException {
                final GitHub gitHub = new GitHubBuilder()
                        .withJwtToken(refreshJWT(appId, privateKey))
                        .build();

                final GHAppInstallationToken token = tokenGetter.getToken(gitHub.getApp());
                return new Jwt(token.getExpiresAt().toInstant(), token.getToken());
            }
        };
    }

    private static String refreshJWT(String appId, PrivateKey privateKey) {
        final Instant now = Instant.now();
        final Instant exp = now.plus(Duration.ofMinutes(10));
        final JwtBuilder builder = Jwts.builder()
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .setIssuer(appId)
                .signWith(privateKey, SignatureAlgorithm.RS256);
        return builder.compact();
    }

    public record Jwt(Instant expirationDate, String jwt) {}

    public interface TokenGetter {
        GHAppInstallationToken getToken(GHApp app) throws IOException;
    }
}
