package eu.faircode.email;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.TypedValue;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

import androidx.preference.PreferenceManager;

public class ContactInfo {
    private String email;
    private Bitmap bitmap;
    private String displayName;
    private Uri lookupUri;
    private long time;

    private static Map<String, LookupInfo> emailLookupInfo = new HashMap<>();
    private static Map<String, ContactInfo> emailContactInfo = new HashMap<>();

    private static final long CACHE_LOOKUP_DURATION = 120 * 60 * 1000L;
    private static final long CACHE_CONTACT_DURATION = 60 * 1000L;

    private ContactInfo() {
    }

    boolean hasPhoto() {
        return (bitmap != null);
    }

    Bitmap getPhotoBitmap() {
        return bitmap;
    }

    String getDisplayName(boolean name_email) {
        if (!name_email && displayName != null)
            return displayName;
        else if (displayName == null)
            return (email == null ? "" : email);
        else
            return displayName + " <" + email + ">";
    }

    boolean hasLookupUri() {
        return (lookupUri != null);
    }

    Uri getLookupUri() {
        return lookupUri;
    }

    private boolean isExpired() {
        return (new Date().getTime() - time > CACHE_CONTACT_DURATION);
    }

    static void clearCache() {
        synchronized (emailLookupInfo) {
            emailLookupInfo.clear();
        }
        synchronized (emailContactInfo) {
            emailContactInfo.clear();
        }
    }

    static ContactInfo get(Context context, Address[] addresses, boolean cacheOnly) {
        if (addresses == null || addresses.length == 0)
            return new ContactInfo();
        InternetAddress address = (InternetAddress) addresses[0];

        String key = MessageHelper.formatAddresses(new Address[]{address});
        synchronized (emailContactInfo) {
            ContactInfo info = emailContactInfo.get(key);
            if (info != null && !info.isExpired())
                return info;
        }

        if (cacheOnly)
            return null;

        ContactInfo info = new ContactInfo();
        info.email = address.getAddress();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (Helper.hasPermission(context, Manifest.permission.READ_CONTACTS))
            try {
                ContentResolver resolver = context.getContentResolver();
                try (Cursor cursor = resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        new String[]{
                                ContactsContract.CommonDataKinds.Photo.CONTACT_ID,
                                ContactsContract.Contacts.LOOKUP_KEY,
                                ContactsContract.Contacts.DISPLAY_NAME
                        },
                        ContactsContract.CommonDataKinds.Email.ADDRESS + " = ? COLLATE NOCASE",
                        new String[]{
                                address.getAddress()
                        }, null)) {

                    if (cursor != null && cursor.moveToNext()) {
                        int colContactId = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Photo.CONTACT_ID);
                        int colLookupKey = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);
                        int colDisplayName = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);

                        long contactId = cursor.getLong(colContactId);
                        String lookupKey = cursor.getString(colLookupKey);
                        Uri lookupUri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey);

                        boolean avatars = prefs.getBoolean("avatars", true);
                        if (avatars) {
                            InputStream is = ContactsContract.Contacts.openContactPhotoInputStream(resolver, lookupUri);
                            info.bitmap = BitmapFactory.decodeStream(is);
                        }

                        info.displayName = cursor.getString(colDisplayName);
                        info.lookupUri = lookupUri;
                    }
                }
            } catch (Throwable ex) {
                Log.e(ex);
            }

        if (info.bitmap == null) {
            boolean identicons = prefs.getBoolean("identicons", false);
            if (identicons) {
                TypedValue tv = new TypedValue();
                context.getTheme().resolveAttribute(R.attr.themeName, tv, true);
                int dp = Helper.dp2pixels(context, 48);
                info.bitmap = Identicon.generate(key, dp, 5, !"light".equals(tv.string));
            }
        }

        if (info.displayName == null)
            info.displayName = address.getPersonal();

        synchronized (emailContactInfo) {
            emailContactInfo.put(key, info);
        }

        info.time = new Date().getTime();
        return info;
    }

    static Uri getLookupUri(Context context, Address[] addresses, boolean useCache) {
        if (!Helper.hasPermission(context, Manifest.permission.READ_CONTACTS))
            return null;

        if (addresses == null || addresses.length == 0)
            return null;
        InternetAddress address = (InternetAddress) addresses[0];

        if (useCache)
            synchronized (emailLookupInfo) {
                LookupInfo info = emailLookupInfo.get(address.getAddress());
                if (info != null && !info.isExpired())
                    return info.uri;
            }

        try {
            ContentResolver resolver = context.getContentResolver();
            try (Cursor cursor = resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Photo.CONTACT_ID,
                            ContactsContract.Contacts.LOOKUP_KEY
                    },
                    ContactsContract.CommonDataKinds.Email.ADDRESS + " = ?",
                    new String[]{
                            address.getAddress()
                    }, null)) {

                Uri uri = null;
                if (cursor != null && cursor.moveToNext()) {
                    int colContactId = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Photo.CONTACT_ID);
                    int colLookupKey = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);

                    long contactId = cursor.getLong(colContactId);
                    String lookupKey = cursor.getString(colLookupKey);

                    uri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey);
                }

                LookupInfo info = new LookupInfo();
                info.uri = uri;
                info.time = new Date().getTime();

                synchronized (emailLookupInfo) {
                    emailLookupInfo.put(address.getAddress(), info);
                }

                return info.uri;
            }
        } catch (Throwable ex) {
            Log.e(ex);
            return null;
        }
    }

    private static class LookupInfo {
        private Uri uri;
        private long time;

        private boolean isExpired() {
            return (new Date().getTime() - time > CACHE_LOOKUP_DURATION);
        }
    }
}
