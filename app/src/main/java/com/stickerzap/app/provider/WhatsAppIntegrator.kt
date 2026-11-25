package com.stickerzap.app.provider

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.stickerzap.app.BuildConfig
import com.stickerzap.app.data.StickerPack
import com.stickerzap.app.data.StickerRepository

object WhatsAppIntegrator {

    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    const val CONSUMER_PACKAGE = "com.whatsapp"
    const val SMB_PACKAGE = "com.whatsapp.w4b"

    private const val WHITELIST_PROVIDER_SUFFIX = ".provider.sticker_whitelist_check"
    private const val WHITELIST_PATH = "is_whitelisted"
    private const val WHITELIST_RESULT_COLUMN = "result"

    sealed class AddPackRequest {
        data class Launch(val intent: Intent) : AddPackRequest()
        data class Error(val message: String) : AddPackRequest()
    }

    fun prepareAdd(context: Context, pack: StickerPack, repo: StickerRepository): AddPackRequest {
        val packDir = repo.packDir(pack.identifier)
        val validationError = PackValidator.verifyAndPrepare(pack, packDir)
        if (validationError != null) return AddPackRequest.Error(validationError)

        val pm = context.packageManager
        val hasConsumer = isPackageInstalled(CONSUMER_PACKAGE, pm)
        val hasSmb = isPackageInstalled(SMB_PACKAGE, pm)
        if (!hasConsumer && !hasSmb) {
            return AddPackRequest.Error(
                "WhatsApp não encontrado neste aparelho. Instale ou atualize o WhatsApp e tente de novo."
            )
        }

        val intent = createAddIntent(pack)
        when {
            hasConsumer && hasSmb -> {
                val inConsumer = isStickerPackWhitelisted(context, pack.identifier, CONSUMER_PACKAGE)
                val inSmb = isStickerPackWhitelisted(context, pack.identifier, SMB_PACKAGE)
                return when {
                    !inConsumer && !inSmb ->
                        AddPackRequest.Launch(Intent.createChooser(intent, "Adicionar ao WhatsApp"))
                    !inConsumer -> AddPackRequest.Launch(intent.apply { setPackage(CONSUMER_PACKAGE) })
                    !inSmb -> AddPackRequest.Launch(intent.apply { setPackage(SMB_PACKAGE) })
                    else -> AddPackRequest.Launch(intent.apply { setPackage(CONSUMER_PACKAGE) })
                }
            }
            hasConsumer -> AddPackRequest.Launch(intent.apply { setPackage(CONSUMER_PACKAGE) })
            else -> AddPackRequest.Launch(intent.apply { setPackage(SMB_PACKAGE) })
        }.let { return it }
    }

    fun createAddIntent(pack: StickerPack): Intent {
        return Intent().apply {
            action = ACTION_ENABLE_STICKER_PACK
            putExtra(EXTRA_STICKER_PACK_ID, pack.identifier)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
        }
    }

    fun messageForResult(resultCode: Int, data: Intent?): String? {
        if (resultCode == Activity.RESULT_OK) {
            return "Álbum adicionado ao WhatsApp."
        }
        if (resultCode == Activity.RESULT_CANCELED) {
            val validationError = data?.getStringExtra(EXTRA_VALIDATION_ERROR)
            if (!validationError.isNullOrBlank()) {
                return "O WhatsApp recusou o álbum:\n$validationError"
            }
            return "O álbum não foi adicionado ao WhatsApp."
        }
        return null
    }

    fun isValidationError(data: Intent?): Boolean {
        return !data?.getStringExtra(EXTRA_VALIDATION_ERROR).isNullOrBlank()
    }

    fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isStickerPackWhitelisted(
        context: Context,
        identifier: String,
        whatsappPackage: String
    ): Boolean {
        val pm = context.packageManager
        if (!isPackageInstalled(whatsappPackage, pm)) return false
        val authority = whatsappPackage + WHITELIST_PROVIDER_SUFFIX
        if (pm.resolveContentProvider(authority, PackageManager.GET_META_DATA) == null) {
            return false
        }
        val uri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(WHITELIST_PATH)
            .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendQueryParameter("identifier", identifier)
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(WHITELIST_RESULT_COLUMN)
                    idx >= 0 && cursor.getInt(idx) == 1
                } else {
                    false
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
