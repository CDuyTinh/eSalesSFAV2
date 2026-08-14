package com.tinhcd.myesalessfa.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val BUCKET = "visit-photos"

/**
 * Uploads visit photos to Supabase Storage.
 *
 * This is the one place that still speaks to Supabase directly for something other
 * than auth. Routing a few hundred kilobytes of JPEG through an Edge Function would
 * send the bytes across the network twice for no gain — the storage API already
 * authorises the write with the rep's own JWT, and the bucket policies already limit
 * each rep to their own folder. Edge Functions shape data; this moves a file.
 *
 * The path convention is fixed by those policies: `<salesperson_id>/<visit_id>/<file>`,
 * the first segment being what the policy authorises on. A path built any other way is
 * refused by storage rather than quietly landing somewhere unowned.
 */
@Singleton
class PhotoUploader @Inject constructor(
    private val client: SupabaseClient,
) {
    /**
     * Uploads one photo and returns its object name.
     *
     * Upsert, because the outbox may replay an entry whose earlier attempt already
     * put some of its photos up. Re-uploading the same bytes to the same path is
     * idempotent; failing on a conflict would strand the audit forever.
     */
    suspend fun upload(
        salespersonId: String,
        visitId: String,
        localPath: String,
    ): String {
        val file = File(localPath)
        if (!file.exists()) {
            // The bytes are gone — cleared cache, or a manual wipe. Nothing here can
            // recover them, and retrying forever would block every later entry in
            // the queue behind an audit that can never succeed.
            error("photo $localPath is no longer on the device")
        }

        val objectName = "$salespersonId/$visitId/${file.name}"

        client.storage.from(BUCKET).upload(path = objectName, data = file.readBytes()) {
            upsert = true
        }

        return objectName
    }

    /** Reclaims the space once the audit has landed. Failure is not worth reporting. */
    fun deleteLocal(localPath: String) {
        runCatching { File(localPath).delete() }
    }
}
