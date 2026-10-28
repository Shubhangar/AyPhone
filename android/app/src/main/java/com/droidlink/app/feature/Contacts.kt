package com.droidlink.app.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.droidlink.app.link.msg
import org.json.JSONArray
import org.json.JSONObject

/** Sends the address book (names and phone numbers only) so the Mac can name callers and senders. */
object Contacts {
    fun listMessage(ctx: Context): JSONObject {
        val contacts = JSONArray()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val byName = LinkedHashMap<String, LinkedHashSet<String>>()
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty()) byName.getOrPut(name) { LinkedHashSet() }.add(number)
                }
            }
            for ((name, numbers) in byName) {
                contacts.put(JSONObject().put("name", name).put("numbers", JSONArray(numbers.toList())))
            }
        }
        return msg("contacts").put("contacts", contacts)
    }
}
