import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Obs2S3Migrate {
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final String DESTINATION_REMOTE = "s3";
    private static final int DEFAULT_TRANSFERS = 8;
    private static final int DEFAULT_CHECKERS = 8;
    private static final int DEFAULT_MULTI_THREAD_STREAMS = 8;
    private static final int DEFAULT_METADATA_SIZE_LIMIT_BYTES = 2048;
    private static final Set<String> BOOLEAN_OPTIONS = Set.of(
            "dry-run",
            "enable-bucket-object-lock",
            "set-lock-after-upload",
            "bypass-governance-retention"
    );

    public static void main(String[] args) {
        try {
            Map<String, String> params = parseArgs(args);

            String sourceLocalPath = params.getOrDefault("source-local-path", "C:\\Users\\ManikantaKapuganti\\test1");
            String sourceRemote = params.getOrDefault("source-remote", "");
            String sourcePath = params.getOrDefault("source-path", "");
            String destinationBucket = params.getOrDefault("destination-bucket", "");
            String destinationPath = params.getOrDefault("destination-path", "");
            String objectLockMode = params.getOrDefault("object-lock-mode", "COMPLIANCE");
            int retentionDays = parseInt(params.get("retention-days"), 0);
            String legalHoldStatus = params.getOrDefault("legal-hold-status", "OFF");
            boolean enableBucketObjectLock = parseBoolean(params, "enable-bucket-object-lock");
            boolean dryRun = parseBoolean(params, "dry-run");
            String s3AccessKeyId = params.getOrDefault("s3-access-key-id", "");
            String s3SecretAccessKey = params.getOrDefault("s3-secret-access-key", "");
            String s3Region = params.getOrDefault("s3-region", "");
            String rcloneExe = params.getOrDefault("rclone-exe", "rclone");
            rcloneExe = resolveRcloneExecutable(rcloneExe);
            int transfers = parseInt(params.get("transfers"), DEFAULT_TRANSFERS);
            int checkers = parseInt(params.get("checkers"), DEFAULT_CHECKERS);
            int multiThreadStreams = parseInt(params.get("multi-thread-streams"), DEFAULT_MULTI_THREAD_STREAMS);
            int metadataSizeLimitBytes = parseInt(params.get("metadata-size-limit-bytes"), DEFAULT_METADATA_SIZE_LIMIT_BYTES);
            String logFile = params.getOrDefault("log-file", "");
            String manifestCsv = params.getOrDefault("manifest-csv", "");

            if (manifestCsv.isEmpty()) {
                throw new IllegalArgumentException("Missing required option: --manifest-csv");
            }

            System.out.println("obs2s3_migrate.java started");
            System.out.println("ManifestCsv: " + manifestCsv);
            System.out.println("LogFile: " + logFile);
            System.out.println("DryRun: " + dryRun);

            Path manifestPath = Paths.get(manifestCsv);
            if (!Files.exists(manifestPath)) {
                throw new IllegalArgumentException("Manifest CSV '" + manifestCsv + "' was not found.");
            }

            List<ManifestRow> manifestRows = readManifestCsv(manifestPath);
            System.out.println("Manifest loaded: " + manifestRows.size() + " rows");

            Path rcloneConfigPath = null;
            if (!dryRun) {
                rcloneConfigPath = createRcloneConfigFile(s3AccessKeyId, s3SecretAccessKey, s3Region);
                System.out.println("Using temporary rclone config: " + rcloneConfigPath);
            }

            Set<String> checkedBuckets = new HashSet<>();

            for (int rowIndex = 0; rowIndex < manifestRows.size(); rowIndex++) {
                ManifestRow row = manifestRows.get(rowIndex);
                String sourceFile = row.get("SourceFile");
                if (sourceFile.isEmpty()) {
                    throw new IllegalArgumentException("Each manifest row must include a SourceFile value.");
                }
                sourceFile = resolveSourcePath(sourceFile, sourceRemote, sourcePath, sourceLocalPath);

                if (sourceRemote.isEmpty() && !Files.exists(Paths.get(sourceFile))) {
                    throw new IllegalArgumentException("Source file '" + sourceFile + "' from manifest row was not found.");
                }

                String bucketName = row.getOrDefault("DestinationBucket", destinationBucket);
                if (bucketName.isEmpty()) {
                    throw new IllegalArgumentException("Each manifest row must include a DestinationBucket value, or you must pass --destination-bucket on the command line.");
                }

                if (enableBucketObjectLock && !checkedBuckets.contains(bucketName)) {
                    if (dryRun) {
                        System.out.println("Dry-run: would create destination bucket '" + bucketName + "' with Object Lock enabled if needed.");
                    } else {
                        if (!bucketExists(bucketName, rcloneExe, rcloneConfigPath, s3Region)) {
                            System.out.println("Creating destination bucket '" + bucketName + "' with Object Lock enabled...");
                            createBucket(bucketName, rcloneExe, rcloneConfigPath, s3Region, logFile);
                        } else {
                            System.out.println("Destination bucket '" + bucketName + "' already exists; skipping bucket creation.");
                        }
                    }
                    checkedBuckets.add(bucketName);
                }

                String pathValue = row.getOrDefault("DestinationPath", destinationPath);
                String sizeCategory = row.getOrDefault("SizeCategory", "");
                String prefix = pathValue.isEmpty() ? sizeCategory : pathValue;
                String destBase = prefix.isEmpty() ? DESTINATION_REMOTE + ":" + bucketName : DESTINATION_REMOTE + ":" + bucketName + "/" + prefix;

                String destFileName = row.getOrDefault("DestinationFileName", "");
                if (destFileName.isEmpty()) {
                    destFileName = Paths.get(sourceFile).getFileName().toString();
                }
                String version = row.getOrDefault("Version", "");
                if (!version.isEmpty()) {
                    String ext = getExtension(destFileName);
                    String baseName = getBaseName(destFileName);
                    destFileName = baseName + "_v" + version + ext;
                }

                String dest = destBase + "/" + destFileName;
                int rowRetentionDays = parseInt(row.get("RetentionDays"), retentionDays);
                if (rowRetentionDays <= 0) {
                    if (retentionDays <= 0) {
                        throw new IllegalArgumentException("Each manifest row must include a RetentionDays value, or you must pass --retention-days on the command line.");
                    }
                    rowRetentionDays = retentionDays;
                }
                String retainUntilDate = Instant.now().atOffset(ZoneOffset.UTC).plusDays(rowRetentionDays)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

                List<String> metadataPairs = sanitizeMetadataPairs(getManifestMetadataPairs(row));
                List<String> tagPairs = convertFromManifestPairs(row.getOrDefault("Tags", ""));
                String tagHeader = buildTagHeader(tagPairs);

                String metadataPayload = String.join(";", metadataPairs);
                int metadataBytes = metadataPayload.isEmpty() ? 0 : metadataPayload.getBytes(UTF8).length;
                String objectKey = pathValue.isEmpty() ? destFileName : pathValue.replaceAll("^/|/$", "") + "/" + destFileName;

                if (!metadataPairs.isEmpty() && metadataBytes > metadataSizeLimitBytes) {
                    System.out.println("Metadata for " + objectKey + " is " + metadataBytes + " bytes, which exceeds the " + metadataSizeLimitBytes + " byte direct-metadata limit; using the oversized-metadata sidecar path.");
                    metadataPairs = processOversizedMetadata(bucketName, objectKey, metadataPairs, rcloneConfigPath, s3Region, retainUntilDate, objectLockMode, legalHoldStatus, checkedBuckets.contains(bucketName), rcloneExe, logFile, transfers, checkers, multiThreadStreams);
                } else if (!metadataPairs.isEmpty()) {
                    System.out.println("Metadata for " + objectKey + " is " + metadataBytes + " bytes, so it will be stored directly on the S3 object as user metadata.");
                }

                List<String> copyArgs = new ArrayList<>();
                copyArgs.add("copyto");
                copyArgs.add(sourceFile);
                copyArgs.add(dest);
                copyArgs.addAll(Arrays.asList(
                        "--transfers", String.valueOf(1),
                        "--checkers", String.valueOf(1),
                        "--multi-thread-streams", String.valueOf(1),
                        "--retries", String.valueOf(3),
                        "--low-level-retries", String.valueOf(10),
                        "--s3-object-lock-mode", objectLockMode,
                        "--s3-object-lock-retain-until-date", retainUntilDate,
                        "--s3-object-lock-legal-hold-status", legalHoldStatus
                ));
                if (!s3AccessKeyId.isEmpty()) {
                    copyArgs.addAll(Arrays.asList("--s3-access-key-id", s3AccessKeyId));
                }
                if (!s3SecretAccessKey.isEmpty()) {
                    copyArgs.addAll(Arrays.asList("--s3-secret-access-key", s3SecretAccessKey));
                }
                if (!s3Region.isEmpty()) {
                    copyArgs.addAll(Arrays.asList("--s3-region", s3Region, "--s3-location-constraint", s3Region));
                }
                if (checkedBuckets.contains(bucketName)) {
                    copyArgs.add("--s3-no-check-bucket");
                }
                if (!logFile.isEmpty()) {
                    copyArgs.addAll(Arrays.asList("--log-file", logFile));
                }
                if (!metadataPairs.isEmpty()) {
                    copyArgs.add("--metadata");
                }
                copyArgs.addAll(buildMetadataArgs(metadataPairs));
                if (!s3Region.isEmpty()) {
                    copyArgs.addAll(Arrays.asList("--s3-region", s3Region, "--s3-location-constraint", s3Region));
                }
                if (!tagHeader.isEmpty()) {
                    copyArgs.add("--header-upload");
                    copyArgs.add(tagHeader);
                }

                System.out.println("Manifest row " + (rowIndex + 1) + ": " + sourceFile + " -> " + dest + " (retention days: " + rowRetentionDays + ")");
                if (dryRun) {
                    System.out.println("Rclone command: " + buildCommandLine(rcloneExe, copyArgs));
                    if (!metadataPairs.isEmpty()) {
                        System.out.println("Metadata to apply: " + String.join("; ", metadataPairs) + " bucket=" + bucketName + " key=" + objectKey);
                    }
                    continue;
                }

                int exitCode = runRcloneUpload(sourceFile, dest, copyArgs, rcloneConfigPath, s3Region, rcloneExe);
                if (exitCode != 0) {
                    throw new IllegalStateException("rclone migration failed for '" + sourceFile + "' with exit code " + exitCode + ".");
                }
                System.out.println("Completed manifest row " + (rowIndex + 1) + ": " + sourceFile + " -> " + dest);
                System.out.println("ROW_COMPLETE_" + (rowIndex + 1));
            }

            System.out.println("Manifest migration complete: " + manifestRows.size() + " rows processed successfully.");
            System.out.println("SCRIPT_COMPLETE");
        } catch (Exception e) {
            System.err.println("obs2s3_migrate.java failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String option = arg.substring(2);
            if (option.contains("=")) {
                String[] parts = option.split("=", 2);
                params.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
            } else if (BOOLEAN_OPTIONS.contains(option)) {
                params.put(option, "true");
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for option: " + arg);
                }
                i++;
                params.put(option.toLowerCase(Locale.ROOT), args[i]);
            }
        }
        return params;
    }

    private static String resolveSourcePath(String sourceFile, String sourceRemote, String sourcePath, String sourceLocalPath) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return sourceLocalPath;
        }
        if (Paths.get(sourceFile).isAbsolute()) {
            return sourceFile;
        }
        if (sourceRemote.isEmpty()) {
            return Paths.get(sourceLocalPath, sourceFile).toString();
        }
        String normalizedPath = sourcePath == null ? "" : sourcePath.trim().replaceAll("^/|/$", "");
        String remoteBase = sourceRemote + ":" + (normalizedPath.isEmpty() ? "" : normalizedPath + "/");
        return remoteBase + sourceFile.replaceAll("^/", "");
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private static boolean parseBoolean(Map<String, String> params, String key) {
        return "true".equalsIgnoreCase(params.getOrDefault(key, "false"));
    }

    private static List<ManifestRow> readManifestCsv(Path csvPath) throws IOException {
        List<String> headers = null;
        List<ManifestRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, UTF8)) {
            String record = null;
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    if (record != null && !record.isBlank()) {
                        List<String> values = parseCsvRecord(record);
                        if (headers == null) {
                            headers = values;
                        } else {
                            rows.add(new ManifestRow(headers, values));
                        }
                    }
                    break;
                }
                if (record == null) {
                    record = line;
                } else {
                    record += "\n" + line;
                }
                if (isCompleteCsvRecord(record)) {
                    List<String> values = parseCsvRecord(record);
                    if (headers == null) {
                        headers = values;
                    } else {
                        rows.add(new ManifestRow(headers, values));
                    }
                    record = null;
                }
            }
        }
        return rows;
    }

    private static boolean isCompleteCsvRecord(String record) {
        int quoteCount = 0;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (c == '"') {
                if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                    i++;
                    continue;
                }
                quoteCount++;
            }
        }
        return quoteCount % 2 == 0;
    }

    private static List<String> parseCsvRecord(String record) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < record.length() && record.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static List<String> getManifestMetadataPairs(ManifestRow row) {
        Map<String, String> metadataMap = new LinkedHashMap<>();
        Set<String> knownColumns = Set.of(
                "SourceFile",
                "DestinationBucket",
                "DestinationPath",
                "DestinationFileName",
                "SizeCategory",
                "RetentionDays",
                "CreatedDate",
                "Version",
                "Metadata",
                "Annotations",
                "Tags"
        );

        for (Map.Entry<String, String> entry : row.values.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name.equalsIgnoreCase("Metadata")) {
                if (!value.isBlank()) {
                    metadataMap.put("metadata", value.trim());
                }
                for (String pair : convertFromManifestPairs(value)) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length != 2) {
                        continue;
                    }
                    String key = parts[0].trim();
                    String valuePart = parts[1].trim();
                    String keyLower = key.toLowerCase(Locale.ROOT);
                    if (keyLower.equals("metadata")) {
                        metadataMap.put("metadata", valuePart);
                    } else if (keyLower.equals("createddate") || keyLower.equals("creationdate")) {
                        metadataMap.put("creationdate", valuePart);
                    } else {
                        metadataMap.put(key, valuePart);
                    }
                }
                continue;
            }

            if (name.equalsIgnoreCase("Annotations")) {
                for (String pair : convertFromManifestPairs(value)) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length != 2) {
                        continue;
                    }
                    metadataMap.put("annotation." + parts[0].trim(), parts[1].trim());
                }
                continue;
            }

            if (knownColumns.contains(name)) {
                if (name.equalsIgnoreCase("CreatedDate") && !value.isEmpty()) {
                    metadataMap.put("creationdate", value);
                }
                if (name.equalsIgnoreCase("Version") && !value.isEmpty()) {
                    metadataMap.put("version", value);
                }
                continue;
            }

            if (!value.isEmpty()) {
                metadataMap.put(name, value);
            }
        }

        return metadataMap.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.toList());
    }

    private static List<String> convertFromManifestPairs(String pairValue) {
        if (pairValue == null || pairValue.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = pairValue.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            List<String> pairs = new ArrayList<>();
            String body = trimmed.substring(1, trimmed.length() - 1);
            Pattern quotedPattern = Pattern.compile("\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"");
            Matcher quotedMatcher = quotedPattern.matcher(body);
            while (quotedMatcher.find()) {
                String key = quotedMatcher.group(1).trim();
                String value = quotedMatcher.group(2).trim();
                if (!key.isEmpty()) {
                    pairs.add(key + "=" + value);
                }
            }
            if (!pairs.isEmpty()) {
                return pairs;
            }
            Pattern unquotedPattern = Pattern.compile("\"([^\"]*)\"\\s*:\\s*(?!\")(?:[^,}]+)");
            Matcher unquotedMatcher = unquotedPattern.matcher(body);
            while (unquotedMatcher.find()) {
                String key = unquotedMatcher.group(1).trim();
                String value = unquotedMatcher.group(2).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    if (value.endsWith(",")) {
                        value = value.substring(0, value.length() - 1).trim();
                    }
                    if (value.endsWith("\"")) {
                        value = value.substring(0, value.length() - 1).trim();
                    }
                    pairs.add(key + "=" + value);
                }
            }
            if (!pairs.isEmpty()) {
                return pairs;
            }
        }
        return Arrays.stream(trimmed.split(";"))
                .map(String::trim)
                .filter(s -> s.contains("="))
                .collect(Collectors.toList());
    }

    private static List<String> sanitizeMetadataPairs(List<String> metadataPairs) {
        if (metadataPairs == null || metadataPairs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sanitized = new ArrayList<>();
        for (String pair : metadataPairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String trimmed = pair.trim();
            int separatorIndex = trimmed.indexOf('=');
            if (separatorIndex <= 0 || separatorIndex >= trimmed.length() - 1) {
                continue;
            }
            String key = trimmed.substring(0, separatorIndex).trim();
            String value = trimmed.substring(separatorIndex + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            key = key.replaceAll("\\s+", "-");
            sanitized.add(key + "=" + value);
        }
        return sanitized;
    }

    private static List<String> buildMetadataArgs(List<String> metadataPairs) {
        List<String> args = new ArrayList<>();
        for (String pair : metadataPairs) {
            args.add("--metadata-set");
            args.add(pair);
        }
        return args;
    }

    private static String buildTagHeader(List<String> tagPairs) {
        if (tagPairs.isEmpty()) {
            return "";
        }
        List<String> encodedPairs = new ArrayList<>();
        for (String pair : tagPairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = urlEncode(parts[0].trim());
            String value = urlEncode(parts[1].trim());
            encodedPairs.add(key + "=" + value);
        }
        if (encodedPairs.isEmpty()) {
            return "";
        }
        return "X-Amz-Tagging: " + String.join("&", encodedPairs);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static List<String> processOversizedMetadata(
            String bucket,
            String objectKey,
            List<String> metadataPairs,
            Path configPath,
            String region,
            String retainUntilDate,
            String objectLockMode,
            String legalHoldStatus,
            boolean noCheckBucket,
            String rcloneExe,
            String logFile,
            int transfers,
            int checkers,
            int multiThreadStreams
    ) throws IOException, InterruptedException {
        System.out.println("Metadata for " + objectKey + " is oversized; applying special handling.");
        List<String> reducedPairs = new ArrayList<>();
        List<String> preferredKeys = Arrays.asList("owner", "environment", "version", "createdDate");
        for (String pair : metadataPairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = parts[0].trim();
            for (String preferred : preferredKeys) {
                if (key.equalsIgnoreCase(preferred)) {
                    if (!reducedPairs.contains(pair)) {
                        reducedPairs.add(pair);
                    }
                }
            }
        }
        if (reducedPairs.isEmpty()) {
            reducedPairs.add("oversizedMetadata=true");
        }

        Map<String, String> metadataObject = convertToCompanionMetadataObject(metadataPairs);
        String sidecarJson = buildSidecarJson(objectKey, metadataObject);
        Path tempDir = Files.createTempDirectory("obs2s3-");
        Path sidecarPath = tempDir.resolve(Paths.get(objectKey).getFileName().toString() + ".metadata.json");
        Files.writeString(sidecarPath, sidecarJson, UTF8);

        String sidecarObjectKey = objectKey.replaceAll("/$", "") + ".metadata.json";
        String sidecarDest = DESTINATION_REMOTE + ":" + bucket + "/" + sidecarObjectKey;
        List<String> sidecarArgs = new ArrayList<>();
        sidecarArgs.add("copyto");
        sidecarArgs.add(sidecarPath.toString());
        sidecarArgs.add(sidecarDest);
        sidecarArgs.addAll(Arrays.asList(
                "--transfers", String.valueOf(transfers),
                "--checkers", String.valueOf(checkers),
                "--multi-thread-streams", String.valueOf(multiThreadStreams),
                "--retries", "3",
                "--low-level-retries", "10",
                "--s3-object-lock-mode", objectLockMode,
                "--s3-object-lock-retain-until-date", retainUntilDate,
                "--s3-object-lock-legal-hold-status", legalHoldStatus
        ));
        if (region != null && !region.isEmpty()) {
            sidecarArgs.addAll(Arrays.asList("--s3-region", region, "--s3-location-constraint", region));
        }
        if (noCheckBucket) {
            sidecarArgs.add("--s3-no-check-bucket");
        }
        if (!logFile.isEmpty()) {
            sidecarArgs.addAll(Arrays.asList("--log-file", logFile));
        }
        sidecarArgs.add("--metadata");
        sidecarArgs.add("--metadata-set");
        sidecarArgs.add("sourceObject=" + objectKey);

        int exitCode = runRcloneUpload(sidecarPath.toString(), sidecarDest, sidecarArgs, configPath, region, rcloneExe);
        if (exitCode != 0) {
            throw new IllegalStateException("Failed to upload sidecar metadata for " + objectKey);
        }
        System.out.println("Oversized metadata sidecar uploaded to: " + sidecarDest);
        reducedPairs.add("metadataSidecar=" + sidecarObjectKey);
        return reducedPairs;
    }

    private static Map<String, String> convertToCompanionMetadataObject(List<String> metadataPairs) {
        Map<String, String> metadataObject = new LinkedHashMap<>();
        for (String pair : metadataPairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = parts[0].trim();
            String value = parts[1].trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                metadataObject.put(key, value);
            }
        }
        if (metadataObject.isEmpty()) {
            metadataObject.put("metadata", "oversized metadata companion object");
        }
        return metadataObject;
    }

    private static String buildSidecarJson(String objectKey, Map<String, String> metadataObject) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"object\":\"").append(escapeJson(objectKey)).append("\",");
        json.append("\"metadata\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : metadataObject.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("\"").append(escapeJson(entry.getKey())).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
        }
        json.append("}}");
        return json.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String resolveRcloneExecutable(String requested) {
        if (requested != null && !requested.isBlank() && !requested.equalsIgnoreCase("rclone")) {
            return requested;
        }
        Path repoRoot = Paths.get(".").toAbsolutePath().normalize();
        Path repoRclone = repoRoot.resolve("rclone.exe");
        if (Files.exists(repoRclone)) {
            return repoRclone.toString();
        }
        Path repoRcloneAlt = repoRoot.resolve("rclone");
        if (Files.exists(repoRcloneAlt)) {
            return repoRcloneAlt.toString();
        }
        return requested == null || requested.isBlank() ? "rclone" : requested;
    }

    private static Path createRcloneConfigFile(String accessKeyId, String secretAccessKey, String region) throws IOException {
        Path configPath = Files.createTempFile("rclone-obs2s3-", ".conf");
        List<String> lines = new ArrayList<>();
        lines.add("[s3]");
        lines.add("type = s3");
        lines.add("provider = AWS");
        if (!accessKeyId.isEmpty()) {
            lines.add("access_key_id = " + accessKeyId);
        }
        if (!secretAccessKey.isEmpty()) {
            lines.add("secret_access_key = " + secretAccessKey);
        }
        if (!region.isEmpty()) {
            lines.add("region = " + region);
        }
        Files.write(configPath, lines, UTF8);
        return configPath;
    }

    private static boolean bucketExists(String bucket, String rcloneExe, Path configPath, String region) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        if (configPath != null) {
            args.addAll(Arrays.asList("--config", configPath.toString()));
        }
        args.addAll(Arrays.asList("lsjson", DESTINATION_REMOTE + ":" + bucket, "--max-depth", "0"));
        if (region != null && !region.isEmpty()) {
            args.addAll(Arrays.asList("--s3-region", region));
        }
        int exitCode = executeProcess(rcloneExe, args, null);
        return exitCode == 0;
    }

    private static void createBucket(String bucket, String rcloneExe, Path configPath, String region, String logFile) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        if (configPath != null) {
            args.addAll(Arrays.asList("--config", configPath.toString()));
        }
        args.addAll(Arrays.asList("mkdir", DESTINATION_REMOTE + ":" + bucket, "--s3-bucket-object-lock-enabled"));
        if (region != null && !region.isEmpty()) {
            args.addAll(Arrays.asList("--s3-region", region, "--s3-location-constraint", region));
        }
        if (!logFile.isEmpty()) {
            args.addAll(Arrays.asList("--log-file", logFile));
        }
        int exitCode = executeProcess(rcloneExe, args, null);
        if (exitCode != 0) {
            throw new IllegalStateException("Failed to create destination bucket '" + bucket + "'.");
        }
    }

    private static int runRcloneUpload(String source, String destination, List<String> additionalArgs, Path configPath, String region, String rcloneExe) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        if (configPath != null) {
            args.addAll(Arrays.asList("--config", configPath.toString()));
        }
        args.addAll(additionalArgs);
        if (region != null && !region.isEmpty()) {
            args.addAll(Arrays.asList("--s3-region", region, "--s3-location-constraint", region));
        }
        return executeProcess(rcloneExe, args, null);
    }

    private static int executeProcess(String executable, List<String> args, Path workingDirectory) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        System.out.println("Running rclone: " + buildCommandLine(executable, args));
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        return process.waitFor();
    }

    private static String buildCommandLine(String executable, List<String> args) {
        List<String> tokens = new ArrayList<>();
        tokens.add(executable);
        for (String arg : args) {
            if (arg.contains(" ") || arg.contains("\"") || arg.contains("^") || arg.contains("&") || arg.contains("|") || arg.contains("<") || arg.contains(">")) {
                tokens.add('"' + arg.replace("\"", "\"\"") + '"');
            } else {
                tokens.add(arg);
            }
        }
        return String.join(" ", tokens);
    }

    private static String getExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx);
    }

    private static String getBaseName(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? fileName : fileName.substring(0, idx);
    }

    private static class ManifestRow {
        private final Map<String, String> values;

        ManifestRow(List<String> headers, List<String> fields) {
            values = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i).trim();
                String value = i < fields.size() ? fields.get(i).trim() : "";
                values.put(header, value);
            }
        }

        String get(String key) {
            return values.getOrDefault(key, "");
        }

        String getOrDefault(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        Map<String, String> getValues() {
            return values;
        }
    }
}
