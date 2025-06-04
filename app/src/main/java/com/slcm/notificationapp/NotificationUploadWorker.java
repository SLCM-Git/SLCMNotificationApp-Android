package com.slcm.notificationapp;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NotificationUploadWorker extends Worker {

    private static final String TAG = "UploadWorker";
    private static final long HTTP_TIMEOUT_SECONDS = 30;


    public NotificationUploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data inputData = getInputData();
        String title = inputData.getString(Constants.KEY_TITLE);
        String description = inputData.getString(Constants.KEY_DESCRIPTION);
        String imagePath = inputData.getString(Constants.KEY_IMAGE_PATH); // This can be null
        String timestamp = inputData.getString(Constants.KEY_TIMESTAMP);
        String cameraId = inputData.getString(Constants.KEY_CAMERA_ID);
        String eventIdentifier = inputData.getString(Constants.KEY_EVENT_IDENTIFIER);
        String source = inputData.getString(Constants.KEY_SOURCE);

        if (title == null || description == null || timestamp == null || cameraId == null || eventIdentifier == null || source == null) {
            if (imagePath != null) deleteTemporaryFile(imagePath);
            return Result.failure();
        }

        if (imagePath != null) {
            Log.d(TAG, "Image path for upload: " + imagePath);
        } else {
            Log.d(TAG, "No image path provided for upload. Proceeding without image.");
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        File imageFile = null;

        try {
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("title", title)
                    .addFormDataPart("description", description)
                    .addFormDataPart("timestamp", timestamp)
                    .addFormDataPart("cameraId", cameraId)
                    .addFormDataPart("eventIdentifier", eventIdentifier)
                    .addFormDataPart("source", source);

            if (imagePath != null && !imagePath.isEmpty()) {
                imageFile = new File(imagePath);
                if (imageFile.exists() && imageFile.isFile()) {
                    try {
                        String extension = getFileExtension(imageFile.getName());
                        MediaType mediaType = MediaType.parse("image/" + (extension.isEmpty() ? "png" : extension));
                        if (mediaType == null) {
                            mediaType = MediaType.parse("application/octet-stream");
                        }

                        multipartBuilder.addFormDataPart("eventImage", imageFile.getName(),
                                RequestBody.create(imageFile, mediaType));
                        Log.d(TAG, "Added image to multipart request: " + imageFile.getName());
                    } catch (Exception e) {
                        Log.e(TAG, "Error preparing image part: " + imageFile.getName() + ". Proceeding without image.", e);
                        imageFile = null;
                    }
                } else {
                    Log.w(TAG, "Image file specified but does not exist or is not a file: " + imagePath + ". Proceeding without image.");
                    imageFile = null;
                }
            }

            RequestBody requestBody = multipartBuilder.build();

            Request request = new Request.Builder()
                    .url(Constants.SERVER_URL)
                    .post(requestBody)
                    .build();

            Log.d(TAG, "Executing HTTP POST request to " + Constants.SERVER_URL);
            try (Response response = client.newCall(request).execute()) {
                String responseBodyString = response.body() != null ? response.body().string() : "No response body";
                if (response.isSuccessful()) {
                    Log.d(TAG, "Upload successful for: " + title + ". Response Code: " + response.code() + ". Response: " + responseBodyString);
                    if (imageFile != null) {
                        deleteTemporaryFile(imagePath);
                    }
                    return Result.success();
                } else {
                    Log.e(TAG, "Upload failed for: " + title + ". Code: " + response.code() + " Message: " + response.message() + ". Response: " + responseBodyString);
                    if (response.code() >= 500 && response.code() < 600) {
                        return Result.retry();
                    } else {
                        if (imageFile != null) {
                            deleteTemporaryFile(imagePath);
                        }
                        return Result.failure();
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException during upload for: " + title + ". Retrying.", e);
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception during upload for: " + title + ". Failing.", e);
            if (imageFile != null) {
                deleteTemporaryFile(imagePath);
            } else if (imagePath != null) {
                File tempFile = new File(imagePath);
                if(tempFile.exists()){
                    deleteTemporaryFile(imagePath);
                }
            }
            return Result.failure();
        }
    }

    private void deleteTemporaryFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return;
        }
        File fileToDelete = new File(filePath);
        if (fileToDelete.exists()) {
            if (fileToDelete.delete()) {
                Log.d(TAG, "Temporary image file deleted successfully: " + filePath);
            } else {
                Log.e(TAG, "Failed to delete temporary image file: " + filePath);

            }
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.lastIndexOf('.') == -1) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
