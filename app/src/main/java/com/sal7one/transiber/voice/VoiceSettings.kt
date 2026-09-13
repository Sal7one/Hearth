package com.sal7one.transiber.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sal7one.transiber.byok.ByokPolicy
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class VoiceChoice(val backend: String="system",val voice: String="F1",val rate: Float=1f,val steps: Int=5)
internal object VoiceSettings {
    fun prefs(context: Context)=context.getSharedPreferences("voice-output",0)
    fun choice(context: Context): VoiceChoice = prefs(context).let {p ->VoiceChoice(
        p.getString("backend","system").orEmpty().let {if(it=="remote" && !ByokPolicy.FEATURE_BYOK)"system" else it},
        p.getString("voice","F1").orEmpty(),p.getFloat("rate",1f).coerceIn(.5f,2f),p.getInt("steps",5).coerceIn(2,12))}
    fun save(context: Context,value: VoiceChoice){require(value.backend in setOf("system","supertonic","remote"));check(value.backend!="remote" || ByokPolicy.FEATURE_BYOK);prefs(context).edit().putString("backend",value.backend).putString("voice",value.voice).putFloat("rate",value.rate).putInt("steps",value.steps).apply()}
    fun remote(context: Context): RemoteVoiceConnection {
        check(ByokPolicy.FEATURE_BYOK){"Network voices are unavailable in the offline build"}
        val p=prefs(context)
        return RemoteVoiceConnection(p.getString("endpoint","").orEmpty(),readKey(context),RemoteVoiceProtocol.capabilities(p.getString("capabilities",null) ?: error("Check the voice server in Voice settings first")))
    }
    fun saveRemote(context: Context,endpoint: String,key: String?,capabilities: String){
        check(ByokPolicy.FEATURE_BYOK);RemoteVoiceProtocol.endpoint(endpoint);RemoteVoiceProtocol.capabilities(capabilities)
        val edit=prefs(context).edit().putString("endpoint",endpoint.trim()).putString("capabilities",capabilities)
        key?.let {if(it.isBlank())edit.remove("secret") else {val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,master());edit.putString("secret",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(it.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))}}
        check(edit.commit()){"Could not save voice connection"}
    }
    fun hasKey(context: Context)=prefs(context).contains("secret")
    fun readKey(context: Context): String {
        check(ByokPolicy.FEATURE_BYOK)
        val saved=prefs(context).getString("secret",null) ?: return ""
        val pair=saved.split(':',limit=2);check(pair.size==2){"Invalid encrypted voice credential"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,master(),GCMParameterSpec(128,Base64.decode(pair[0],Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(pair[1],Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun forget(context: Context){prefs(context).edit().remove("secret").remove("capabilities").remove("endpoint").remove("remote-voice").putString("backend","system").apply()}
    private fun master(): SecretKey {
        val alias="hearth_voice_v1";val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
}
