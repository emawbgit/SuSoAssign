package org.emawbgit.susoassign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A tab-delimited file: header + rows. Always UTF-8 (Java 11 defaults to cp1252 on Windows). */
final class Tab {
    final String name;               // file name without extension
    final List<String> header;
    final List<String[]> rows;

    Tab(String name, List<String> header, List<String[]> rows) {
        this.name = name;
        this.header = header;
        this.rows = rows;
    }

    int col(String c) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).equalsIgnoreCase(c)) return i;
        return -1;
    }

    /**
     * Parse tab-delimited text.
     *
     * @param rfcQuotes true for Stata's export delimited output ("a""b" quoting); false for
     *                  Survey Solutions files, which HQ reads with CsvMode.NoEscape (quotes are literal).
     */
    static Tab parse(String name, String text, boolean rfcQuotes) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        List<String[]> recs = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        int i = 0, n = text.length();
        boolean any = false;
        while (i < n) {
            char c = text.charAt(i);
            if (rfcQuotes && c == '"' && f.length() == 0) {
                i++;
                while (i < n) {
                    char d = text.charAt(i++);
                    if (d == '"') {
                        if (i < n && text.charAt(i) == '"') { f.append('"'); i++; }
                        else break;
                    } else f.append(d);
                }
                any = true;
                continue;
            }
            if (c == '\t') { cur.add(f.toString()); f.setLength(0); any = true; i++; continue; }
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                i++;
                if (any || f.length() > 0) { cur.add(f.toString()); recs.add(cur.toArray(new String[0])); }
                cur = new ArrayList<>(); f.setLength(0); any = false;
                continue;
            }
            f.append(c); any = true; i++;
        }
        if (any || f.length() > 0) { cur.add(f.toString()); recs.add(cur.toArray(new String[0])); }
        if (recs.isEmpty()) return new Tab(name, new ArrayList<>(), new ArrayList<>());
        List<String> header = new ArrayList<>();
        for (String h : recs.get(0)) header.add(h.trim());
        List<String[]> rows = new ArrayList<>();
        for (int r = 1; r < recs.size(); r++) {
            String[] src = recs.get(r);
            String[] row = new String[header.size()];
            for (int k = 0; k < row.length; k++) row[k] = k < src.length ? src[k] : "";
            rows.add(row);
        }
        return new Tab(name, header, rows);
    }

    /** Write in the Survey Solutions flavour: no quoting; tabs and line breaks inside values become spaces. */
    void write(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        line(sb, header.toArray(new String[0]));
        for (String[] r : rows) line(sb, r);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void line(StringBuilder sb, String[] cells) {
        for (int k = 0; k < cells.length; k++) {
            if (k > 0) sb.append('\t');
            String v = cells[k] == null ? "" : cells[k];
            sb.append(v.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
        }
        sb.append("\r\n");
    }

    static boolean isTabFile(String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        return f.endsWith(".tab") || f.endsWith(".txt");
    }

    static String baseName(String fileName) {
        String f = fileName.replace('\\', '/');
        f = f.substring(f.lastIndexOf('/') + 1);
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }

    /** Read every .tab/.txt file from a folder or a .zip archive. */
    static List<Tab> readAll(Path src, boolean rfcQuotes) throws IOException {
        List<Tab> out = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
                for (Path p : ds) {
                    if (Files.isRegularFile(p) && isTabFile(p.getFileName().toString()))
                        out.add(parse(baseName(p.getFileName().toString()),
                                new String(Files.readAllBytes(p), StandardCharsets.UTF_8), rfcQuotes));
                }
            }
        } else if (Files.isRegularFile(src)) {
            String low = src.getFileName().toString().toLowerCase(Locale.ROOT);
            if (low.endsWith(".zip")) {
                try (ZipFile z = new ZipFile(src.toFile(), StandardCharsets.UTF_8)) {
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory() || !isTabFile(e.getName())) continue;
                        if (e.getName().replace('\\', '/').contains("__MACOSX/")) continue;
                        try (InputStream in = z.getInputStream(e)) {
                            out.add(parse(baseName(e.getName()), new String(readAllBytes(in), StandardCharsets.UTF_8), rfcQuotes));
                        }
                    }
                }
            } else if (isTabFile(low)) {
                out.add(parse(baseName(src.getFileName().toString()),
                        new String(Files.readAllBytes(src), StandardCharsets.UTF_8), rfcQuotes));
            } else {
                throw new IOException("Expected a .zip, a folder, or a .tab file: " + src);
            }
        } else {
            throw new IOException("Not found: " + src);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int k;
        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        return bo.toByteArray();
    }
}
