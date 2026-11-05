package com.commitnotes;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import nl.martijndwars.webpush.PushService;
import org.junit.jupiter.api.Test;

class PushSenderTest {
  private static byte[] fixed(BigInteger value) {
    byte[] raw=value.toByteArray(),out=new byte[32];
    System.arraycopy(raw,Math.max(0,raw.length-32),out,Math.max(0,32-raw.length),Math.min(32,raw.length)); return out;
  }
  @Test void loadsVapidKeysWithTheBouncyCastleProvider() throws Exception {
    new PushSender(null);
    KeyPairGenerator generator=KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair=generator.generateKeyPair(); ECPublicKey pub=(ECPublicKey)pair.getPublic();
    byte[] point=new byte[65]; point[0]=4;
    System.arraycopy(fixed(pub.getW().getAffineX()),0,point,1,32); System.arraycopy(fixed(pub.getW().getAffineY()),0,point,33,32);
    Base64.Encoder encoder=Base64.getUrlEncoder().withoutPadding();
    String publicKey=encoder.encodeToString(point),privateKey=encoder.encodeToString(fixed(((ECPrivateKey)pair.getPrivate()).getS()));
    assertDoesNotThrow(()->new PushService(publicKey,privateKey,"mailto:test@example.test"));
  }
}
