'use strict';
// RSA-OAEP(SHA-256) wraps a random AES-256-GCM key; the QR token authenticates the payload.
function binaryRandom(length) {
  if (!globalThis.crypto || !crypto.getRandomValues) throw new Error('This browser cannot create a secure setup request. Use your TV’s paired phone keyboard.');
  return String.fromCharCode(...crypto.getRandomValues(new Uint8Array(length)));
}
function encryptSetup(value, encodedPublicKey, pairingToken) {
  const bytes = encodedPublicKey.replace(/-/g, '+').replace(/_/g, '/');
  const rsa = forge.pki.publicKeyFromAsn1(forge.asn1.fromDer(forge.util.decode64(bytes)));
  const key = binaryRandom(32), nonce = binaryRandom(12);
  const cipher = forge.cipher.createCipher('AES-GCM', key);
  cipher.start({iv: nonce, additionalData: pairingToken, tagLength: 128});
  cipher.update(forge.util.createBuffer(forge.util.encodeUtf8(JSON.stringify(value))));
  if (!cipher.finish()) throw new Error('Could not encrypt setup. Nothing was sent.');
  return {v: 1, wrappedKey: forge.util.encode64(rsa.encrypt(key, 'RSA-OAEP', {
    md: forge.md.sha256.create(), mgf1: {md: forge.md.sha256.create()}
  })), nonce: forge.util.encode64(nonce), ciphertext: forge.util.encode64(cipher.output.getBytes() + cipher.mode.tag.getBytes())};
}
globalThis.HearthSetupCrypto = Object.freeze({encrypt: encryptSetup});
