package it.belloworld.mercurygram;

import org.json.JSONException;
import org.json.JSONObject;

public class MgUpdateInfo {
    public String versionName;
    public String downloadUrl;
    public long fileSize;
    public String changelog;
    public String tagName;
    public String apkFileName;

    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("versionName", versionName);
            json.put("downloadUrl", downloadUrl);
            json.put("fileSize", fileSize);
            json.put("changelog", changelog);
            json.put("tagName", tagName);
            json.put("apkFileName", apkFileName);
            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    public static MgUpdateInfo fromJson(String jsonStr) {
        if (jsonStr == null) return null;
        try {
            JSONObject json = new JSONObject(jsonStr);
            MgUpdateInfo info = new MgUpdateInfo();
            info.versionName = json.optString("versionName");
            info.downloadUrl = json.optString("downloadUrl");
            info.fileSize = json.optLong("fileSize");
            info.changelog = json.optString("changelog");
            info.tagName = json.optString("tagName");
            info.apkFileName = json.optString("apkFileName");
            return info;
        } catch (JSONException e) {
            return null;
        }
    }
}
