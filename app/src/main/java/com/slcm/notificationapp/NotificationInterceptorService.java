package com.slcm.notificationapp;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationInterceptorService extends NotificationListenerService {

    private static final String TAG = "NotificationListener";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);

        String packageName = sbn.getPackageName();
        Log.d(TAG, "Notification posted from package: " + packageName);

        if (!Constants.TARGET_PACKAGE_NAME.equals(packageName)) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null) {
            Log.w(TAG, "Notification object is null.");
            cancelNotification(sbn.getKey());
            return;
        }

        Bundle extras = notification.extras;
        if (extras == null) {
            Log.w(TAG, "Notification extras are null.");
            cancelNotification(sbn.getKey());
            return;
        }

        String title = extras.getString(Notification.EXTRA_TITLE);
        CharSequence textChars = extras.getCharSequence(Notification.EXTRA_TEXT);
        String description = (textChars != null) ? textChars.toString() : null;

        if (description == null) {
            CharSequence bigTextChars = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            description = (bigTextChars != null) ? bigTextChars.toString() : null;
        }

        if (title == null || description == null) {
            Log.w(TAG, "Notification title or description is null. Title: " + title + ", Desc: " + description);
            cancelNotification(sbn.getKey());
            return;
        }

        Log.d(TAG, "Title: " + title);
        Log.d(TAG, "Description: " + description);

        String lowerDesc = description.toLowerCase(Locale.ROOT);
        if (!lowerDesc.equals(Constants.DESC_HUMAN_DETECTION.toLowerCase(Locale.ROOT)) &&
                !lowerDesc.equals(Constants.DESC_MOTION_DETECTION.toLowerCase(Locale.ROOT))) {
            Log.d(TAG, "Notification description does not match criteria. Skipping. Description: " + description);
            cancelNotification(sbn.getKey());
            return;
        }

        String cameraId = parseCameraId(title);
        if (cameraId == null || cameraId.isEmpty()) {
            Log.w(TAG, "Could not parse Camera ID from title: " + title + ". Skipping notification.");
            cancelNotification(sbn.getKey());
            return;
        }
        Log.d(TAG, "Parsed Camera ID: " + cameraId);

        Bitmap notificationImageBitmap = getNotificationImage(notification);
        String imagePath = null;
        if (notificationImageBitmap != null) {
            imagePath = saveBitmapToFile(getApplicationContext(), notificationImageBitmap, "event_image_" + System.currentTimeMillis() + ".png");
            if (imagePath == null) {
                Log.e(TAG, "Failed to save notification image to file. Proceeding without image.");
            } else {
                Log.d(TAG, "Image saved to: " + imagePath);
            }
        } else {
            Log.w(TAG, "No image found in notification. Proceeding without image.");
        }

        long timestamp = System.currentTimeMillis();
        String eventIdentifier = description;
        String source = getApplicationContext().getPackageName();

        Data.Builder inputDataBuilder = new Data.Builder()
                .putString(Constants.KEY_TITLE, title)
                .putString(Constants.KEY_DESCRIPTION, description)
                .putString(Constants.KEY_TIMESTAMP, formatUnixTimestampToISO8601(timestamp))
                .putString(Constants.KEY_CAMERA_ID, cameraId)
                .putString(Constants.KEY_EVENT_IDENTIFIER, eventIdentifier)
                .putString(Constants.KEY_SOURCE, source);

        if (imagePath != null) {
            inputDataBuilder.putString(Constants.KEY_IMAGE_PATH, imagePath);
        }

        Data inputData = inputDataBuilder.build();

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest uploadWorkRequest =
                new OneTimeWorkRequest.Builder(NotificationUploadWorker.class)
                        .setInputData(inputData)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                TimeUnit.MILLISECONDS)
                        .addTag(Constants.WORKER_TAG)
                        .build();

        WorkManager.getInstance(getApplicationContext()).enqueue(uploadWorkRequest);
        Log.d(TAG, "Work request enqueued for notification: " + title + (imagePath != null ? " with image." : " without image."));

        cancelNotification(sbn.getKey());
    }


    private String parseCameraId(String title) {
        if (title == null) return null;

        Pattern pattern = Pattern.compile("^(.*?)\\s+\\d+\\s+channel.*$", Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(title);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        Log.w(TAG, "Camera ID pattern not found in title: " + title);

        return null;
    }

    public String formatUnixTimestampToISO8601(long unixMillis) {
        Date date = new Date(unixMillis);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    private Bitmap getNotificationImage(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return null;

        if (extras.containsKey(Notification.EXTRA_PICTURE)) {
            Bitmap bitmap = extras.getParcelable(Notification.EXTRA_PICTURE);
            if (bitmap != null) {
                Log.d(TAG, "Image found in EXTRA_PICTURE.");
                return bitmap;
            }
        }

        if (notification.largeIcon != null) {

            Log.d(TAG, "Image found in largeIcon (fallback).");
            return notification.largeIcon;
        }


        Log.d(TAG, "No image found in notification extras (EXTRA_PICTURE) or largeIcon.");
        return null;
    }

    private String saveBitmapToFile(Context context, Bitmap bitmap, String fileName) {
        if (bitmap == null) {
            Log.e(TAG, "Bitmap is null, cannot save to file.");
            return null;
        }
        File cacheDir = context.getCacheDir();
        if (!cacheDir.exists()) {
            if (!cacheDir.mkdirs()) {
                Log.e(TAG, "Failed to create cache directory.");
                return null;
            }
        }
        File imageFile = new File(cacheDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            Log.d(TAG, "Bitmap saved to: " + imageFile.getAbsolutePath());
            return imageFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Error saving bitmap to file: " + fileName, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error saving bitmap to file: " + fileName, e);
            return null;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "Notification Listener connected.");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "Notification Listener disconnected.");
    }
}
