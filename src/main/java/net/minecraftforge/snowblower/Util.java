package net.minecraftforge.snowblower;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class Util {
    static Path getPath(URI uri) {
        try {
            return Path.of(uri);
        } catch (FileSystemNotFoundException e) {
            if (uri.getScheme().equals("jar")) {
                try {
                    FileSystems.newFileSystem(uri, Map.of());
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
                return Path.of(uri);
            }

            throw e;
        }
    }

    static boolean isDev() {
        return Main.class.getPackage().getImplementationVersion() == null;
    }

    static Path getSourcePath() {
        // This should never be a jar if in dev
        Path folderPath = Path.of(getCodeSourceUri());

        while (!Files.exists(folderPath.resolve(".git"))) {
            folderPath = folderPath.getParent();
            if (folderPath == null)
                return null;
        }

        return folderPath;
    }

    static URI getCodeSourceUri() {
        try {
            return Util.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
