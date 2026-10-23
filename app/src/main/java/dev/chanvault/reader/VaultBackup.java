package dev.chanvault.reader;

import android.app.Activity;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Explicit ZIP export/import via Android's document picker, not public Gallery access. */
final class VaultBackup {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long FILE_LIMIT = 250L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 3L * 1024 * 1024 * 1024;
    private static final int ENTRY_LIMIT = 3000;

    static void exportTo(Activity context, Uri destination, Runnable success) {
        IO.execute(() -> {
            try {
                File root = new File(context.getFilesDir(), "vault");
                MainActivity.Vault vault = new MainActivity.Vault(root);
                JSONArray index = new JSONArray();
                ArrayList<File> files = new ArrayList<>();
                for (JSONObject item : vault.list()) {
                    String filename = item.optString("file");
                    if (!safeFile(filename)) continue;
                    File file = new File(root, filename);
                    if (file.isFile()) { files.add(file); index.put(item); }
                }
                try (OutputStream output = context.getContentResolver().openOutputStream(destination);
                     ZipOutputStream zip = new ZipOutputStream(output)) {
                    if (output == null) throw new Exception("Cannot write to selected location");
                    zip.putNextEntry(new ZipEntry("index.json"));
                    zip.write(index.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    byte[] bytes = new byte[16384];
                    for (File file : files) {
                        zip.putNextEntry(new ZipEntry("media/" + file.getName()));
                        try (InputStream in = new FileInputStream(file)) {
                            int n;
                            while ((n = in.read(bytes)) != -1) zip.write(bytes, 0, n);
                        }
                        zip.closeEntry();
                    }
                }
                context.runOnUiThread(() -> {
                    Toast.makeText(context, "Vault backup exported. Store this ZIP privately.", Toast.LENGTH_LONG).show();
                    success.run();
                });
            } catch (Exception ex) {
                context.runOnUiThread(() -> Toast.makeText(context,
                        "Backup failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    static void importFrom(Activity context, Uri source, Runnable success) {
        IO.execute(() -> {
            File root = new File(context.getFilesDir(), "vault");
            File stage = new File(root, ".restore_" + System.currentTimeMillis());
            try {
                if (!new MainActivity.Vault(root).list().isEmpty())
                    throw new Exception("Restore requires an empty vault. Export your current files first.");
                if (!stage.mkdirs()) throw new Exception("Cannot create temporary restore folder");
                JSONArray index = null;
                Set<String> received = new HashSet<>();
                long total = 0;
                int count = 0;
                try (InputStream sourceStream = context.getContentResolver().openInputStream(source);
                     ZipInputStream zip = new ZipInputStream(sourceStream)) {
                    if (sourceStream == null) throw new Exception("Cannot read selected backup");
                    ZipEntry entry;
                    byte[] buf = new byte[16384];
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory()) { zip.closeEntry(); continue; }
                        if (++count > ENTRY_LIMIT) throw new Exception("Backup contains too many items");
                        String name = entry.getName();
                        if (!"index.json".equals(name) &&
                                !(name.startsWith("media/") && safeFile(name.substring(6)))) {
                            throw new Exception("Invalid backup entry");
                        }
                        ByteArrayOutputStream indexBuffer = "index.json".equals(name) ? new ByteArrayOutputStream() : null;
                        File part = indexBuffer == null ? new File(stage, name.substring(6)) : null;
                        if (part != null && !received.add(part.getName())) throw new Exception("Duplicate media entry");
                        long bytesForEntry = 0;
                        try (OutputStream out = indexBuffer != null ? indexBuffer : new FileOutputStream(part)) {
                            int n;
                            while ((n = zip.read(buf)) != -1) {
                                bytesForEntry += n;
                                total += n;
                                if (bytesForEntry > FILE_LIMIT && indexBuffer == null ||
                                        total > TOTAL_LIMIT || indexBuffer != null && bytesForEntry > 4 * 1024 * 1024)
                                    throw new Exception("Backup exceeds storage limits");
                                out.write(buf, 0, n);
                            }
                        }
                        if (indexBuffer != null)
                            index = new JSONArray(indexBuffer.toString("UTF-8"));
                        zip.closeEntry();
                    }
                }
                if (index == null) throw new Exception("Backup index is missing");
                for (int i = 0; i < index.length(); i++) {
                    JSONObject item = index.optJSONObject(i);
                    if (item == null || !safeFile(item.optString("file")) ||
                            !received.contains(item.optString("file")))
                        throw new Exception("Backup index is invalid or incomplete");
                }
                if (received.size() != index.length())
                    throw new Exception("Unindexed files found in backup");
                // Only publish the index after media files are in place.
                for (String name : received) {
                    File incoming = new File(stage, name);
                    File dest = new File(root, name);
                    if (dest.exists() || !incoming.renameTo(dest))
                        throw new Exception("Could not restore " + name);
                }
                File tmpIndex = new File(root, "index.json.restore");
                Files.write(tmpIndex.toPath(), index.toString().getBytes(StandardCharsets.UTF_8));
                if (!tmpIndex.renameTo(new File(root, "index.json")))
                    throw new Exception("Could not publish restored library");
                context.runOnUiThread(() -> {
                    Toast.makeText(context, "Private library restored successfully", Toast.LENGTH_LONG).show();
                    success.run();
                });
            } catch (Exception ex) {
                context.runOnUiThread(() -> Toast.makeText(context,
                        "Restore failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                File[] remains = stage.listFiles();
                if (remains != null) for (File f : remains) f.delete();
                stage.delete();
            }
        });
    }

    private static boolean safeFile(String name) {
        return name != null && name.matches("[a-f0-9-]{36}\\.(mp4|webm|jpg|jpeg|png|gif)");
    }

    private VaultBackup() {}
}
