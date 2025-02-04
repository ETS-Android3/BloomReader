package org.sil.bloom.reader;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import androidx.annotation.IntDef;

import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.sil.bloom.reader.models.BookOrShelf;
import org.sil.bloom.reader.models.BookCollection;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.Enumeration;

import static org.sil.bloom.reader.BloomReaderApplication.getBloomApplicationContext;
import static org.sil.bloom.reader.models.BookCollection.getLocalBooksDirectory;


public class IOUtilities {
    public static final String[] BOOK_FILE_EXTENSIONS = {".bloompub", ".bloomd"};
    public static final String BOOKSHELF_FILE_EXTENSION = ".bloomshelf";
    public static final String BLOOM_BUNDLE_FILE_EXTENSION = ".bloombundle";
    public static final String CHECKED_FILES_TAG = "org.sil.bloom.reader.checkedfiles";

    // Some file transfer mechanisms leave this appended to .bloompub/.bloomd (or .bloombundle)
    public static final String ENCODED_FILE_EXTENSION = ".enc";

    private static final int BUFFER_SIZE = 8192;

    public static void showError(Context context, CharSequence message) {
        int duration = Toast.LENGTH_SHORT;

        Toast toast = Toast.makeText(context, message, duration);
        toast.show();
    }

    public static void emptyDirectory(File dir) {
        if (dir == null)
            return;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File child : files)
                deleteFileOrDirectory(child);
        }
    }

    // Returns true if the file name's extension indicates this file
    // is a bloomPUB (.bloompub or .bloomd)
    public static boolean isBloomPubFile(String fileName, boolean includeEncoded) {
        for (String bookFileExtension : BOOK_FILE_EXTENSIONS) {
            if (fileName.endsWith(bookFileExtension))
                return true;
            if (includeEncoded && fileName.endsWith(bookFileExtension + ENCODED_FILE_EXTENSION))
                return true;
        }
        return false;
    }
    public static boolean isBloomPubFile(String fileName) {
        return isBloomPubFile(fileName, false);
    }

    public static String stripBookFileExtension(String fileName) {
        for (String bookFileExtension : BOOK_FILE_EXTENSIONS)
            fileName = fileName.replace(bookFileExtension, "");
        return fileName;
    }

    public static File getBookFileIfExists(String title) {
        File localBookDirectory = BookCollection.getLocalBooksDirectory();
        for (String bookFileExtension : BOOK_FILE_EXTENSIONS) {
            File file = new File(localBookDirectory, title + bookFileExtension);
            if (file.exists())
                return file;
        }
        return null;
    }

    public static String ensureFileNameHasNoEncodedExtension(String filename) {
        if (isBloomPubFile(filename, true) && filename.endsWith(IOUtilities.ENCODED_FILE_EXTENSION)) {
            filename = filename.substring(0, filename.length() - IOUtilities.ENCODED_FILE_EXTENSION.length());
        }
        return filename;
    }

    public static boolean isDirectoryEmpty(File dir) {
        return dir.listFiles().length == 0;
    }

    public static void deleteFileOrDirectory(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory())
            for (File child : fileOrDirectory.listFiles())
                deleteFileOrDirectory(child);
        fileOrDirectory.delete();
    }

    //from http://stackoverflow.com/a/27050680
    public static void unzip(ZipInputStream zis, File targetDirectory) throws IOException {
        try {
            ZipEntry ze;
            int count;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((ze = zis.getNextEntry()) != null) {
                File file = new File(targetDirectory, ze.getName());

                // Prevent path traversal vulnerability. See https://support.google.com/faqs/answer/9294009.
                String fileCanonicalPath = file.getCanonicalPath();
                if (!fileCanonicalPath.startsWith(targetDirectory.getCanonicalPath())) {
                    throw new IOException(String.format("Zip file target path is invalid: %s", fileCanonicalPath));
                }

                File dir = ze.isDirectory() ? file : file.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs())
                    throw new FileNotFoundException("Failed to ensure directory: " +
                            dir.getAbsolutePath());
                if (ze.isDirectory())
                    continue;
                FileOutputStream fout = new FileOutputStream(file);
                try {
                    while ((count = zis.read(buffer)) != -1) {
                        if (count == 0) {
                            // The zip header says we have more data for this entry, but we don't.
                            // The file must be truncated/corrupted.  See BL-6970.
                            throw new IOException("Invalid zip file");
                        }
                        fout.write(buffer, 0, count);
                    }
                } finally {
                    fout.close();
                }
            /* if time should be restored as well
            long time = ze.getTime();
            if (time > 0)
                file.setLastModified(time);
            */
            }
        } finally {
            zis.close();
        }
    }

    public static void unzip(File zipFile, File targetDirectory) throws IOException {
        ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)));
        unzip(zis, targetDirectory);
    }

    public static void unzip(Context context, Uri uri, File targetDirectory) throws IOException {
        InputStream fs = context.getContentResolver().openInputStream(uri);
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fs));
        unzip(zis, targetDirectory);
    }

    // Possible types of zip files to check.  (This list could be expanded if desired.)
    public static final int CHECK_ZIP = 0;
    public static final int CHECK_BLOOMPUB = 1;

    @IntDef({CHECK_ZIP, CHECK_BLOOMPUB})  // more efficient than enum types at run time.
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileChecks {
    }

    private static SharedPreferences sCheckedFiles = null;

    // Check whether the given input file is a valid zip file.
    public static boolean isValidZipFile(File input) {
        return isValidZipFile(input, CHECK_ZIP);
    }


    public static boolean isValidZipFile(File input, @FileChecks int checkType) {
        return isValidZipFile(input, checkType, null);
    }

    // Check whether the given input file is a valid zip file that appears to have the proper data
    // for the given type.  We record the result of this check in a "SharedPreferences" file with
    // the modification time paired with the absolute pathname of the file.  If these match on the
    // next call, we'll return true without actually going through the slow process of unzipping
    // the whole file.  Note that this fast bypass ignores the checkType and desiredFile parameters.
    // The desiredFile parameter is designed to avoid having to unzip the file twice during startup,
    // once to ensure that it is valid and once to get the meta.json file content.
    public static boolean isValidZipFile(File input, @FileChecks int checkType, TextFileContent desiredFile) {
        String key = input.getAbsolutePath();
        if (sCheckedFiles == null) {
            Context context = getBloomApplicationContext();
            if (context != null) {
                sCheckedFiles = context.getSharedPreferences(CHECKED_FILES_TAG, 0);
            }
        }
        if (sCheckedFiles != null) {
            long timestamp = sCheckedFiles.getLong(key, 0L);
            if (timestamp == input.lastModified() && timestamp != 0L)
                return true;
        }
        try {
            // REVIEW very minimal check for .bloompub/.bloomd files: are there any filenames guaranteed to exist
            // in any .bloompub/.bloomd file regardless of age?
            int countHtml = 0;
            int countCss = 0;
            final ZipFile zipFile = new ZipFile(input);
            try {
                final Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory())
                        continue;
                    String entryName = entry.getName().toLowerCase(Locale.ROOT);
                    // For validation purposes we're only interested in html files in the root directory.
                    // Activities, for example, may legitimately have their own.
                    if ((entryName.endsWith(".htm") || entryName.endsWith(".html")) && entryName.indexOf("/")< 0)
                        ++countHtml;
                    else if (entryName.endsWith(".css"))
                        ++countCss;
                    InputStream stream = zipFile.getInputStream(entry);
                    try {
                        int realSize = (int)entry.getSize();
                        byte[] buffer = new byte[realSize];
                        boolean inOnePass = true;   // valid data only if it's read by a single read operation.
                        int size = stream.read(buffer);
                        if (size != realSize && !(size == -1 && realSize == 0)) {
                            // The Java ZipEntry code does not always return the full data content even when the buffer is large
                            // enough for it.  Whether this is a bug or a feature, or just the way it is, depends on your point
                            // of view I suppose.  So we have a loop here since the initial read wasn't enough.
                            inOnePass = false;
                            int moreReadSize = stream.read(buffer);
                            do {
                                if (moreReadSize > 0) {
                                    size += moreReadSize;
                                    moreReadSize = stream.read(buffer);
                                }
                            } while (moreReadSize > 0);
                            if (size != realSize) {
                                // It would probably throw before getting here, but just in case, write
                                // out some debugging information and return false.
                                int compressedSize = (int)entry.getCompressedSize();
                                int method = entry.getMethod();
                                String type = "UNKNOWN (" + method + ")";
                                switch (entry.getMethod()) {
                                    case ZipEntry.STORED:
                                        type = "STORED";
                                        break;
                                    case ZipEntry.DEFLATED:
                                        type = "DEFLATED";
                                        break;
                                }
                                Log.e("IOUtilities", "Unzip size read " + size + " != size expected " + realSize +
                                        " for " + entry.getName() + " in " + input.getName() + ", compressed size = " + compressedSize + ", storage method = " + type);
                                return false;
                            }
                        }
                        if (inOnePass && desiredFile != null && entryName.equals(desiredFile.getFilename())) {
                            // save the desired file content so we won't have to unzip again
                            desiredFile.Content = new String(buffer, desiredFile.getEncoding());
                        }
                    } finally {
                        stream.close();
                    }
                }
            } finally {
                zipFile.close();
            }
            boolean retval;
            if (checkType == IOUtilities.CHECK_BLOOMPUB)
                retval = countHtml == 1 && countCss > 0;
            else
                retval = true;
            if (retval && sCheckedFiles != null) {
                SharedPreferences.Editor editor = sCheckedFiles.edit();
                editor.putLong(key, input.lastModified());
                editor.apply();
            }
            return retval;
        } catch (Exception e) {
            return false;
        }
    }

    // The same test, but here we only have available a URI.
    public static boolean isValidZipUri(Uri input, @FileChecks int checkType, TextFileContent desiredFile) {
        String key = input.toString();
        Context context = getBloomApplicationContext();
        if (sCheckedFiles == null) {
            if (context != null) {
                sCheckedFiles = context.getSharedPreferences(CHECKED_FILES_TAG, 0);
            }
        }
        if (sCheckedFiles != null) {
            long timestamp = sCheckedFiles.getLong(key, 0L);
            if (timestamp == lastModified(context, input) && timestamp != 0L)
                return true;
        }
        try {
            // REVIEW very minimal check for .bloompub files: are there any filenames guaranteed to exist
            // in any .bloompub file regardless of age?
            int countHtml = 0;
            int countCss = 0;
            InputStream fs = context.getContentResolver().openInputStream(input);
            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fs));
            try {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory())
                        continue;
                    String entryName = entry.getName().toLowerCase(Locale.ROOT);
                    // For validation purposes we're only interested in html files in the root directory.
                    // Activities, for example, may legitimately have their own.
                    if ((entryName.endsWith(".htm") || entryName.endsWith(".html")) && entryName.indexOf("/") < 0)
                        ++countHtml;
                    else if (entryName.endsWith(".css"))
                        ++countCss;
                    int realSize = (int) entry.getSize();
                    byte[] buffer = new byte[realSize];
                    if (realSize != 0) {
                        // The Java ZipEntry code does not always return the full data content even when the buffer is large
                        // enough for it.  Whether this is a bug or a feature, or just the way it is, depends on your point
                        // of view I suppose.  So we have a loop here in case the initial read wasn't enough.
                        int size = 0;
                        int moreReadSize = zis.read(buffer, size, realSize - size);
                        while (moreReadSize > 0) {
                                size += moreReadSize;
                                moreReadSize = zis.read(buffer, size, realSize - size);
                        } ;
                        if (size != realSize) {
                            // It would probably throw before getting here, but just in case, write
                            // out some debugging information and return false.
                            int compressedSize = (int) entry.getCompressedSize();
                            int method = entry.getMethod();
                            String type = "UNKNOWN (" + method + ")";
                            switch (entry.getMethod()) {
                                case ZipEntry.STORED:
                                    type = "STORED";
                                    break;
                                case ZipEntry.DEFLATED:
                                    type = "DEFLATED";
                                    break;
                            }
                            Log.e("IOUtilities", "Unzip size read " + size + " != size expected " + realSize +
                                    " for " + entry.getName() + " in " + BookOrShelf.getNameFromPath(input.getPath()) + ", compressed size = " + compressedSize + ", storage method = " + type);
                            return false;
                        }
                    }
                    if (desiredFile != null && entryName.equals(desiredFile.getFilename())) {
                        // save the desired file content so we won't have to unzip again
                        desiredFile.Content = new String(buffer, desiredFile.getEncoding());
                    }
                }
            } finally {
                zis.close();
                fs.close();
            }
            boolean retval;
            if (checkType == IOUtilities.CHECK_BLOOMPUB)
                retval = countHtml == 1 && countCss > 0;
            else
                retval = true;
            if (retval && sCheckedFiles != null) {
                SharedPreferences.Editor editor = sCheckedFiles.edit();
                editor.putLong(key, lastModified(context, input));
                editor.apply();
            }
            return retval;
        } catch (Exception e) {
            return false;
        }
    }

    public static long lastModified(Context context, Uri uri) {
        if (uri == null) return 0; // sometimes we make a URI for a file that might not exist, like something.modified.
        if (uri.getScheme().equals("file")) {
            return new File(uri.getPath()).lastModified(); // returns zero if anything goes wrong.
        }
        if (uri.getScheme().equals("content")) {
            // SAF type URIs.
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst())
                    return cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED));
            } catch (Exception e) {
                e.printStackTrace();
                return 0;
            }
        }
        assert false; // some scheme we know nothing about.
        return 0;
    }

    // Return a 'File' object representing the old Bloom directory where Bloom used to store book
    // files when the OS allowed it.
    public static File getOldBloomBooksFolder(Context context) {
        File oldBookDirectory = null;
        File[] appFilesDirs = context.getExternalFilesDirs(null);
        for (File appFilesDir : appFilesDirs) {
            if (appFilesDir != null) {
                oldBookDirectory = appFilesDir;
                break;
            }
        }
        if (oldBookDirectory == null)
            return null; // huh??
        return new File(IOUtilities.storageRootFromAppFilesDir(oldBookDirectory), "Bloom");
    }

    public static void createOldBloomBooksFolder(Context context) {
        if (!BaseActivity.haveLegacyStoragePermission(context)) return;
        File oldBloomDirectory = getOldBloomBooksFolder(context);
        if (oldBloomDirectory == null) return;
        oldBloomDirectory.mkdirs();
    }

    public static byte[] ExtractZipEntry(Context context, Uri uri, String entryName) {
        InputStream fs = null;
        try {
            fs = context.getContentResolver().openInputStream(uri);
            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fs));
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null
                    && !ze.getName().equals(entryName)) {
            }
            if (ze == null)
                return new byte[0];
            int size = (int) ze.getSize();
            final byte[] output = new byte[size];
            int offset = 0;
            int count = 0;
            while ((count = zis.read(output, offset, output.length - offset)) > 0) {
                offset += count;
            }
            return output;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new byte[0];
    }

    public static byte[] ExtractZipEntry(File input, String entryName) {
        try {
            ZipFile zip = new ZipFile(input);
            try {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    return null;
                }
                InputStream stream = zip.getInputStream(entry);
                try {
                    byte[] buffer = new byte[(int) entry.getSize()];
                    stream.read(buffer);
                    return buffer;
                } finally {
                    stream.close();
                }
            } finally {
                zip.close();
            }
        } catch (IOException e)
        {
            return null;
        }
    }

    public static boolean copyAssetFolder(AssetManager assetManager,
                                          String fromAssetPath, String toPath) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            new File(toPath).mkdirs();
            boolean res = true;
            for (String file : files)
                if (file.contains("."))
                    res &= copyAsset(assetManager,
                            fromAssetPath + File.separator + file,
                            toPath + File.separator + file);
                else
                    res &= copyAssetFolder(assetManager,
                            fromAssetPath + File.separator + file,
                            toPath + File.separator + file);
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean copyAsset(AssetManager assetManager, String fromAssetPath, String toFilePath) {
        try {
            InputStream in = assetManager.open(fromAssetPath);
            return copyFile(in, toFilePath);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean copyFile(InputStream fromStream, String toPath) {

        File output = new File(toPath);
        try {
            readFileFromInput(fromStream, output);
            return true;
        } catch (Exception e) {
            output.delete();  // A partial file causes problems (BL-6970), so delete what we copied.
            return false;
        }
    }
    public static void readFileFromInput(InputStream fromStream, File output) throws IOException {
        int totalRead = 0;
        try {
        output.getParentFile().mkdirs(); // can't find a clear answer on whether createNewFile will do this
        output.createNewFile(); //this does nothing if if already exists
        OutputStream out = new FileOutputStream(output);
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = fromStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            totalRead += read;
        }
        fromStream.close();
        out.flush();
        out.close();
        } catch (Exception e) {
            Log.e("IOUtilities", "Copied "+totalRead+" bytes to "+output.getPath()+" before failing ("+e.getMessage()+")");
            e.printStackTrace();
            throw e;
        }
    }

    public static boolean copyFile(String fromPath, String toPath) {
        try {
            InputStream in = new FileInputStream(fromPath);
            return copyFile(in, toPath);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean copyBookOrShelfFile(Context context, Uri bookOrShelfUri, String toPath) {
        try {
            InputStream in = context.getContentResolver().openInputStream(bookOrShelfUri);
            if (copyFile(in, toPath)) {
                // Even if the copy succeeds, if the result is not a valid book or shelf file, delete it
                // and fail.
                File newFile = new File(toPath);
                boolean validFile = toPath.endsWith(BOOKSHELF_FILE_EXTENSION)
                    ? BloomShelfFileReader.isValidShelf(context, bookOrShelfUri)
                        : isValidZipFile(newFile, CHECK_BLOOMPUB);
                if (!validFile) {
                    newFile.delete();
                    return false;
                }
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static String FileToString(File file) {
        try {
            return InputStreamToString(new FileInputStream(file));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String UriToString(Context context, Uri uri) {
        try {
            return InputStreamToString(context.getContentResolver().openInputStream(uri));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String InputStreamToString(InputStream inputStream) {
        try {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // We want it to end with this extension and be a top-level file in the directory.
    public static File findFirstWithExtension(File directory, final String extension){
        return findFirstMatching(directory, name -> name.endsWith(extension) && name.indexOf("/") < 0);
    }

    // Return first file whose name (relative to directory) satisfies the condition.
    public static File findFirstMatching(File directory, Predicate<String> condition){
        File[] paths = directory.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File file, String name) {
                return condition.test(name);
            }
        });

        if (paths.length >= 1)
            return paths[0];
        return null;
    }

    public static void tar(String directory, FilenameFilter filter, String destinationPath) throws IOException {
        File[] fileList = new File(directory).listFiles(filter);
        tar(fileList, destinationPath);
    }

    public static void tar(File[] files, String destinationPath) throws IOException {
        File destination = new File(destinationPath);
        File destDirectory = destination.getParentFile();
        if (!destDirectory.exists())
            destDirectory.mkdirs();

        TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(new FileOutputStream(destinationPath));
        tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
        for(File file : files) {
            String bookFileName = file.getName();
            // Here we don't want to rename bloom shelves. And eventually we will take this out.
            // But currently older bloom readers won't handle bloombundles containing bloompubs.
            if (bookFileName.endsWith(".bloompub")) {
                int index = bookFileName.lastIndexOf(".");
                if (index >= 0) {
                    bookFileName = bookFileName.substring(0, index);
                }
                bookFileName += ".bloomd";
            }
            TarArchiveEntry entry = new TarArchiveEntry(file, bookFileName);
            tarOutput.putArchiveEntry(entry);
            FileInputStream in = new FileInputStream(file);
            IOUtils.copy(in, tarOutput);
            in.close();
            tarOutput.closeArchiveEntry();
        }
        tarOutput.close();
    }

    public static void makeBloomBundle(String destinationPath) throws IOException {

        FilenameFilter filter = (dir, filename) ->
                isBloomPubFile(filename) || filename.endsWith(BOOKSHELF_FILE_EXTENSION);
        tar(getLocalBooksDirectory().getAbsolutePath(), filter, destinationPath);
        //zip(getLocalBooksDirectory().getAbsolutePath(), filter, destinationPath);
    }

    public static String extractTarEntry(TarArchiveInputStream tarInput, String targetPath) throws IOException {
        ArchiveEntry entry = tarInput.getCurrentEntry();
        File destPath=new File(targetPath,entry.getName());
        if (!entry.isDirectory()) {
            FileOutputStream fout=new FileOutputStream(destPath);
            try{
                final byte[] buffer=new byte[8192];
                int n=0;
                while (-1 != (n=tarInput.read(buffer))) {
                    fout.write(buffer,0,n);
                }
                fout.close();
            }
            catch (IOException e) {
                fout.close();
                destPath.delete();
                tarInput.close();
                throw e;
            }
        }
        else {
            destPath.mkdir();
        }
        return destPath.getPath();
    }

    public static File nonRemovablePublicStorageRoot(Context context) {
        return publicStorageRoot(context, false);
    }

    public static File removablePublicStorageRoot(Context context) {
        return publicStorageRoot(context, true);
    }

    private static File publicStorageRoot(Context context, boolean removable) {
        if (Environment.isExternalStorageRemovable() == removable)
            return Environment.getExternalStorageDirectory();

        File[] appFilesDirs = context.getExternalFilesDirs(null);
        for (File appFilesDir : appFilesDirs) {
            if (appFilesDir != null) {
                File root  = storageRootFromAppFilesDir(appFilesDir);
                if (root != null && isRemovable(root) == removable)
                    return root;
            }
        }
        return null;
    }

    public static int countFilesRecursively(File root, FileFilter filter) {
        return listFilesRecursively(root, filter, null);
    }

    public static File[] listFilesRecursively(File root, FileFilter filter) {
        ArrayList<File> accumulator = new ArrayList<>();
        int count = listFilesRecursively(root, filter, accumulator);
        return accumulator.toArray(new File[count]);
    }

    public static int listFilesRecursively(File root, FileFilter filter, ArrayList<File> result) {
        final int[] count = new int[] {0};
        root.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (file.isDirectory()) {
                    count[0] += listFilesRecursively(file, filter, result);
                }
                if (filter == null || filter.accept(file)) {
                    count[0]++;
                    if (result != null) {
                        result.add(file);
                    }
                }
                // We're not using the result of listFiles, so no need for it to build up an array,
                // whether the file passed or not.
                return false;
            }
        });
        return count[0];
    }

    private static boolean isRemovable(File dir) {
        return Environment.isExternalStorageRemovable(dir);
    }

    public static File storageRootFromAppFilesDir(File appFilesDir) {
        // appStorageDir is a directory within the public storage with a path like
        // /path/to/public/storage/Android/data/org.sil.bloom.reader/files

        String path = appFilesDir.getPath();
        int androidDirIndex = path.indexOf(File.separator + "Android" + File.separator);
        if (androidDirIndex > 0)
            return new File(path.substring(0, androidDirIndex));
        return null;
    }

    static String getFilename(String path) {
        // Check for colon because files on SD card root have a path like
        // 1234-ABCD:book.bloompub
        int start = Math.max(path.lastIndexOf(File.separator),
                             path.lastIndexOf(':'))
                    + 1;
        return path.substring(start);
    }

    public static String getFileNameFromUri(Context context, Uri uri) {
        String fileName = null;
        try {
            String fileNameOrPath = IOUtilities.getFileNameOrPathFromUri(context, uri);
            if (fileNameOrPath != null && !fileNameOrPath.isEmpty())
                fileName = IOUtilities.getFilename(fileNameOrPath);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return fileName;
    }

    public static String getFileNameOrPathFromUri(Context context, Uri uri) {
        String nameOrPath = uri.getPath();
        // Content URI's do not use the actual filename in the "path"
        if (uri.getScheme().equals("content")) {
            ContentResolver contentResolver = context.getContentResolver();
            if (contentResolver == null) // Play console showed us this could be null somehow
                return null;
            try (Cursor cursor = contentResolver.query(uri, null, null, null, null)) {
                if (cursor != null) {
                    if (cursor.moveToFirst())
                        nameOrPath = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } catch (SecurityException se) {
                // Not sure how this happens, but we see it on the Play Console.
                // Perhaps someone has chosen Bloom Reader to try to process an intent we shouldn't be trying to handle?
                return null;
            }
        }
        return nameOrPath;
    }
}
