package com.sal7one.transiber.voice

import android.content.Context
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.DownloadAssets
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The authenticated 31 MB tokenizer ranges share the durable model queue and notification.
 * The central worker refuses a full-archive response and resumes inside either pinned range.
 */
internal object MagpieTokenizerDownload {
    fun enqueue(context: Context, downloads: FileDownloads = FileDownloads(context)): Long {
        check(ByokPolicy.FEATURE_BYOK) { "Tokenizer downloads are unavailable in the offline build; import a verified tokenizer range file" }
        MagpiePolicy.requireAllowed(context, "Magpie tokenizer download")
        val asset = checkNotNull(DownloadAssets.find(DownloadAssets.MAGPIE_TOKENIZER))
        return downloads.enqueue(DownloadSpec.parse(asset.url, "magpie-tokenizer.ranges"), true, asset.id)
    }
    suspend fun install(context: Context) = withContext(Dispatchers.IO) { enqueue(context); Unit }
}
