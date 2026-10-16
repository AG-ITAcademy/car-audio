package ro.ag.caraudio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import java.io.FileNotFoundException

/**
 * Gives the images in assets/art (mood tiles, tab icons) to Android Auto, which can
 * load local artwork only through a content:// URI. Read-only, fixed file names.
 */
class ArtProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val name = uri.lastPathSegment
        if (mode != "r" || name == null || !NAME.matches(name)) throw FileNotFoundException(uri.toString())
        return context!!.assets.openFd("art/$name")
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/jpeg"

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0

    companion object {
        private const val AUTHORITY = "ro.ag.caraudio.art"
        private val NAME = Regex("""[a-z0-9_]+\.(png|jpg)""")

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
