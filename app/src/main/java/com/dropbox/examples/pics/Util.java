package com.dropbox.examples.pics;

public class Util {

    static String stripExtension(String extension, String filename) {
        extension = "." + extension;
        if (filename.endsWith(extension)) {
            return filename.substring(0, filename.length() - extension.length());
        }
        return filename;
    }
}
