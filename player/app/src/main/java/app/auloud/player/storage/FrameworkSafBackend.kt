package app.auloud.player.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException

/**
 * WP3/WP5 refinement: [SafBackend] over the framework only
 * (`ContentResolver` + `DocumentsContract`; no androidx.documentfile, no new
 * dependency).
 *
 * Document IDs follow the tree convention: `<treeDocId>/<relPath>` (e.g.
 * `primary:Auloud/book1/manifest.json`), resolved with
 * `buildDocumentUriUsingTree`. Children come from
 * `buildChildDocumentsUriUsingTree` + a `query` for id/name/mime. All calls
 * used exist since API 19/21, fine for minSdk 24.
 *
 * Errors: [children] and [readText] throw `IOException` (permission loss
 * included) so rescan surfaces them; [exists] returns false instead — a
 * missing manifest/cover is a validation message, never a crash.
 */
class FrameworkSafBackend(
    private val resolver: ContentResolver,
    private val treeUriString: String
) : SafBackend {

    private fun treeUri(): Uri = Uri.parse(treeUriString)

    private fun treeDocId(): String =
        DocumentsContract.getTreeDocumentId(treeUri())

    private fun docUriFor(relPath: String): Uri {
        val rel = relPath.trim().trim('/')
        val id = SafPaths.documentId(treeDocId(), rel)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri(), id)
    }

    override fun children(parentRel: String): List<SafChild> {
        val parent = parentRel.trim().trim('/')
        val parentId = SafPaths.documentId(treeDocId(), parent)
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri(), parentId)
        val out = mutableListOf<SafChild>()
        val cursor = try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            ) ?: throw IOException("$treeUriString: cannot list $parentRel")
        } catch (e: IOException) {
            throw e
        } catch (e: SecurityException) {
            throw IOException("$treeUriString: permission lost for $parentRel: ${e.message}", e)
        } catch (e: Exception) {
            throw IOException("$treeUriString: cannot list $parentRel: ${e.message}", e)
        }
        cursor.use { c ->
            val nameCol =
                c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol =
                c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val name = try {
                    c.getString(nameCol)
                } catch (e: Exception) {
                    Log.w(TAG, "child name unreadable: ${e.message}")
                    null
                } ?: continue
                val mime = try {
                    c.getString(mimeCol)
                } catch (e: Exception) {
                    null
                }
                out.add(SafChild(name, mime == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
        return out
    }

    override fun exists(relPath: String): Boolean {
        if (relPath.isBlank()) return true
        return try {
            resolver.query(
                docUriFor(relPath),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun readText(relPath: String): String {
        if (relPath.isBlank()) {
            throw IOException("$treeUriString: blank document path")
        }
        val uri = docUriFor(relPath)
        try {
            resolver.openInputStream(uri)?.use { stream ->
                return stream.readBytes().toString(Charsets.UTF_8)
            } ?: throw IOException("$relPath: cannot open document")
        } catch (e: IOException) {
            throw e
        } catch (e: SecurityException) {
            throw IOException("$relPath: permission denied: ${e.message}", e)
        } catch (e: Exception) {
            throw IOException("$relPath: cannot read document: ${e.message}", e)
        }
    }

    override fun documentUri(relPath: String): String =
        docUriFor(relPath).toString()

    companion object {
        /** Log tag (`Auloud*`, <= 23 chars for API 24). */
        const val TAG = "AuloudStorage"
    }
}
