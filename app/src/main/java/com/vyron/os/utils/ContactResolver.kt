package com.vyron.os.utils

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

object ContactResolver {
    private const val TAG = "ContactResolver"

    data class ContactMatch(
        val name: String,
        val phoneNumber: String
    )

    fun resolve(context: Context, query: String): List<ContactMatch> {
        val matches = mutableListOf<ContactMatch>()
        if (query.trim().isEmpty()) return matches

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            cursor?.use { c ->
                val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                if (nameIndex != -1 && numberIndex != -1) {
                    while (c.moveToNext()) {
                        val name = c.getString(nameIndex)
                        val rawNumber = c.getString(numberIndex)
                        
                        // Clean number formatting for comparisons and duplicates
                        val number = cleanPhoneNumber(rawNumber)
                        if (number.isNotEmpty()) {
                            val match = ContactMatch(name, number)
                            if (!matches.contains(match)) {
                                matches.add(match)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts provider: ${e.message}", e)
        }

        Log.d(TAG, "Resolved ${matches.size} contact matches for query: '$query'")
        return matches
    }

    private fun cleanPhoneNumber(number: String): String {
        // Remove spaces, hyphens, and parentheses
        return number.replace(Regex("[\\s\\-+()]"), "")
    }
}
