package com.swag617.restartsched.backup;

import com.swag617.restartsched.SwagRestartScheduler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Plain {@link Runnable} that performs the actual backup work entirely
 * off the main server thread.
 *
 * <p>This class must never call any Bukkit API — it is dispatched
 * asynchronously by {@link BackupManager#runBackup(Runnable)} and
 * the result is handed back via the {@code resultCallback} consumer,
 * which BackupManager re-schedules onto the main thread.</p>
 */
public class BackupTask implements Runnable {

    private static final DateTimeFormatter FILENAME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final int BUFFER_SIZE = 65_536; // 64 KB copy buffer

    private final Logger logger;
    private final File backupDir;
    private final List<File> sourceFolders;
    private final boolean compress;
    private final Consumer<Boolean> resultCallback;

    /**
     * @param plugin          plugin instance (used only for its logger)
     * @param backupDir       destination directory where the backup file/folder is written
     * @param sourceFolders   resolved source folders to include in the backup
     * @param compress        {@code true} = produce a single zip file;
     *                        {@code false} = flat recursive copy into a timestamped subfolder
     * @param resultCallback  called with {@code true} on success, {@code false} on failure;
     *                        BackupManager schedules this back onto the main thread
     */
    public BackupTask(SwagRestartScheduler plugin, File backupDir,
                      List<File> sourceFolders, boolean compress,
                      Consumer<Boolean> resultCallback) {
        this.logger         = plugin.getLogger();
        this.backupDir       = backupDir;
        this.sourceFolders   = sourceFolders;
        this.compress        = compress;
        this.resultCallback  = resultCallback;
    }

    // -------------------------------------------------------------------------
    // Runnable
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        // Ensure the destination directory exists
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            logger.severe("[Backup] Could not create backup directory: " + backupDir.getAbsolutePath());
            resultCallback.accept(false);
            return;
        }

        String timestamp = LocalDateTime.now().format(FILENAME_FMT);
        String baseName  = "backup_" + timestamp;

        try {
            if (compress) {
                runZipBackup(baseName);
            } else {
                runFlatCopy(baseName);
            }
            resultCallback.accept(true);
        } catch (Exception e) {
            logger.severe("[Backup] Backup failed with exception: " + e.getMessage());
            resultCallback.accept(false);
        }
    }

    // -------------------------------------------------------------------------
    // Zip backup
    // -------------------------------------------------------------------------

    private void runZipBackup(String baseName) throws IOException {
        File zipFile = new File(backupDir, baseName + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);

            for (File source : sourceFolders) {
                if (!source.exists()) {
                    logger.warning("[Backup] Source folder does not exist, skipping: "
                            + source.getAbsolutePath());
                    continue;
                }
                addFolderToZip(zos, source, source.getName());
            }
        }

        double sizeMb = zipFile.length() / 1024.0 / 1024.0;
        logger.info(String.format("[Backup] Backup complete: %s (%.1f MB)",
                zipFile.getName(), sizeMb));
    }

    /**
     * Recursively adds {@code folder} and all its contents into the zip under
     * {@code entryPrefix}. Uses forward slashes for zip entry paths regardless
     * of the host OS path separator.
     */
    private void addFolderToZip(ZipOutputStream zos, File folder, String entryPrefix)
            throws IOException {
        File[] children = folder.listFiles();
        if (children == null) return; // empty dir or access error — skip silently

        for (File child : children) {
            String entryName = entryPrefix + "/" + child.getName();

            if (child.isDirectory()) {
                addFolderToZip(zos, child, entryName);
            } else {
                if (!child.canRead()) {
                    logger.warning("[Backup] Cannot read file, skipping: " + child.getAbsolutePath());
                    continue;
                }
                try (FileInputStream fis = new FileInputStream(child)) {
                    // Normalise to forward-slash paths (zip spec requirement)
                    ZipEntry entry = new ZipEntry(entryName.replace('\\', '/'));
                    zos.putNextEntry(entry);

                    byte[] buf = new byte[BUFFER_SIZE];
                    int    len;
                    while ((len = fis.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                    zos.closeEntry();
                } catch (IOException e) {
                    // Log and continue — a single unreadable file should not abort the backup
                    logger.warning("[Backup] Skipping unreadable file: "
                            + child.getAbsolutePath() + " — " + e.getMessage());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Flat-copy backup
    // -------------------------------------------------------------------------

    private void runFlatCopy(String baseName) throws IOException {
        File destRoot = new File(backupDir, baseName);
        if (!destRoot.mkdirs()) {
            throw new IOException("Could not create backup folder: " + destRoot.getAbsolutePath());
        }

        long totalBytes = 0L;

        for (File source : sourceFolders) {
            if (!source.exists()) {
                logger.warning("[Backup] Source folder does not exist, skipping: "
                        + source.getAbsolutePath());
                continue;
            }
            File destFolder = new File(destRoot, source.getName());
            totalBytes += copyFolderRecursive(source, destFolder);
        }

        double sizeMb = totalBytes / 1024.0 / 1024.0;
        logger.info(String.format("[Backup] Backup complete: %s (%.1f MB)", baseName, sizeMb));
    }

    /**
     * Recursively copies {@code src} into {@code dest}, creating directories
     * as needed.
     *
     * @return total bytes copied in this subtree
     */
    private long copyFolderRecursive(File src, File dest) throws IOException {
        long total = 0L;

        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("Could not create directory: " + dest.getAbsolutePath());
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    total += copyFolderRecursive(child, new File(dest, child.getName()));
                }
            }
        } else {
            if (!src.canRead()) {
                logger.warning("[Backup] Cannot read file, skipping: " + src.getAbsolutePath());
                return 0L;
            }
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            total += src.length();
        }

        return total;
    }
}
