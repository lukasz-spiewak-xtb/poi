/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */
package org.apache.poi.poifs.crypt.standard;

import static org.apache.poi.poifs.crypt.CryptoFunctions.hashPassword;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.crypt.ChainingMode;
import org.apache.poi.poifs.crypt.CryptoFunctions;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionHeader;
import org.apache.poi.poifs.crypt.EncryptionVerifier;
import org.apache.poi.poifs.crypt.HashAlgorithm;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.util.LittleEndian;

/**
 */
public class StandardDecryptor extends Decryptor {
    private long _length = -1;

    protected StandardDecryptor() {}

    protected StandardDecryptor(StandardDecryptor other) {
        super(other);
        _length = other._length;
    }

    @Override
    public boolean verifyPassword(String password) {
        EncryptionVerifier ver = getEncryptionInfo().getVerifier();
        SecretKey skey = generateSecretKey(password, ver, getKeySizeInBytes());
        Cipher cipher = getCipher(skey);

        try {
            byte[] encryptedVerifier = ver.getEncryptedVerifier();
            byte[] verifier = cipher.doFinal(encryptedVerifier);
            setVerifier(verifier);
            MessageDigest sha1 = CryptoFunctions.getMessageDigest(ver.getHashAlgorithm());
            byte[] calcVerifierHash = sha1.digest(verifier);
            byte[] encryptedVerifierHash = ver.getEncryptedVerifierHash();
            byte[] decryptedVerifierHash = cipher.doFinal(encryptedVerifierHash);

            // see 2.3.4.9 Password Verification (Standard Encryption)
            // ... The number of bytes used by the encrypted Verifier hash MUST be 32 ...
            // TODO: check and trim/pad the hashes to 32
            byte[] verifierHash = Arrays.copyOf(decryptedVerifierHash, calcVerifierHash.length);

            if (Arrays.equals(calcVerifierHash, verifierHash)) {
                setSecretKey(skey);
                return true;
            } else {
                return false;
            }
        } catch (GeneralSecurityException e) {
            throw new EncryptedDocumentException(e);
        }
    }

    protected static SecretKey generateSecretKey(String password, EncryptionVerifier ver, int keySize) {
        HashAlgorithm hashAlgo = ver.getHashAlgorithm();

        byte[] pwHash = hashPassword(password, hashAlgo, ver.getSalt(), ver.getSpinCount());

        byte[] blockKey = new byte[4];
        LittleEndian.putInt(blockKey, 0, 0);

        byte[] finalHash = CryptoFunctions.generateKey(pwHash, hashAlgo, blockKey, hashAlgo.hashSize);
        byte[] x1 = fillAndXor(finalHash, (byte) 0x36);
        byte[] x2 = fillAndXor(finalHash, (byte) 0x5c);

        byte[] x3 = new byte[x1.length + x2.length];
        System.arraycopy(x1, 0, x3, 0, x1.length);
        System.arraycopy(x2, 0, x3, x1.length, x2.length);

        byte[] key = Arrays.copyOf(x3, keySize);

        return new SecretKeySpec(key, ver.getCipherAlgorithm().jceId);
    }

    protected static byte[] fillAndXor(byte[] hash, byte fillByte) {
        byte[] buff = new byte[64];
        Arrays.fill(buff, fillByte);

        for (int i=0; i<hash.length; i++) {
            buff[i] = (byte) (buff[i] ^ hash[i]);
        }

        MessageDigest sha1 = CryptoFunctions.getMessageDigest(HashAlgorithm.sha1);
        return sha1.digest(buff);
    }

    private Cipher getCipher(SecretKey key) {
        EncryptionHeader em = getEncryptionInfo().getHeader();
        ChainingMode cm = em.getChainingMode();
        assert(cm == ChainingMode.ecb);
        return CryptoFunctions.getCipher(key, em.getCipherAlgorithm(), cm, null, Cipher.DECRYPT_MODE);
    }

    @Override
    @SuppressWarnings({"resource", "squid:S2095"})
    public InputStream getDataStream(DirectoryNode dir) throws IOException {
        DocumentInputStream dis = dir.createDocumentInputStream(DEFAULT_POIFS_ENTRY);

        _length = dis.readLong();

        if(getSecretKey() == null) {
            verifyPassword(null);
        }
        // limit wrong calculated ole entries - (bug #57080)
        // standard encryption always uses aes encoding, so blockSize is always 16
        int blockSize = getEncryptionInfo().getHeader().getCipherAlgorithm().blockSize;
        long cipherLen = (_length/blockSize + 1) * blockSize;
        Cipher cipher = getCipher(getSecretKey());

        final InputStream boundedDis = BoundedInputStream.builder()
            .setInputStream(dis)
            .setMaxCount(cipherLen)
            .get();
        return BoundedInputStream.builder()
            .setInputStream(new CipherInputStream(boundedDis, cipher))
            .setMaxCount(_length)
            .get();
    }

    /**
     * @return the length of the stream returned by {@link #getDataStream(DirectoryNode)}
     */
    @Override
    public long getLength(){
        if(_length == -1) {
            throw new IllegalStateException("Decryptor.getDataStream() was not called");
        }
        return _length;
    }

    @Override
    public StandardDecryptor copy() {
        return new StandardDecryptor(this);
    }
}
