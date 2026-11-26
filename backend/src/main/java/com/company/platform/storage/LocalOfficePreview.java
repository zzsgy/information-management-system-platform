package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.operations.DeploymentSettings;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** 在服务器私有临时目录内将公盘 Office 原件转换为 PDF。 */
@Component
public class LocalOfficePreview {
    private final DeploymentSettings settings;
    /** 配置快照让一次转换与其缓存标识使用同一个程序。 */
    public record Configuration(Path executable, Path temporary, String cacheKey) {}

    public LocalOfficePreview(DeploymentSettings settings) {
        this.settings = settings;
    }

    /** 留空沿用已部署的文档服务。配置了本机程序时提前核对路径和文件版本。 */
    public Configuration configuration() {
        String executable = settings.effective(DeploymentSettings.LOCAL_OFFICE_EXECUTABLE);
        if (executable.isBlank()) return null;
        String temporary = settings.effective("platform.reading.temporary");
        if (temporary.isBlank()) throw SystemFailure.READING_NOT_CONFIGURED.fault();
        try {
            Path binary = DeploymentSettings.path(executable, "LibreOffice 程序");
            var attributes = Files.readAttributes(binary, BasicFileAttributes.class);
            if (!binary.getFileName().toString().matches("(?i)soffice(\\.com|\\.exe)?")
                || !attributes.isRegularFile() || !Files.isExecutable(binary)) {
                throw SystemFailure.LOCAL_OFFICE_UNAVAILABLE.fault();
            }
            Path folder = DeploymentSettings.path(temporary, "阅读临时目录");
            String key = "libreoffice-v1:" + Crypto.hash(binary + "|" + attributes.size() + "|" + attributes.lastModifiedTime()).substring(0, 40);
            return new Configuration(binary, folder, key);
        } catch (IOException | IllegalArgumentException failure) {
            throw SystemFailure.LOCAL_OFFICE_UNAVAILABLE.fault(failure);
        }
    }

    /** 使用独立配置目录运行一次转换。输入扩展名由已发布原件的实测格式限定。 */
    Path convert(Configuration configuration, Path source, String input, long maximum) throws IOException {
        Path root = PrivateFiles.directory(configuration.temporary().toString());
        Path work = Files.createTempDirectory(root, "office-preview-");
        try {
            PrivateFiles.restrict(work);
            Path document = work.resolve("document." + input);
            copyInput(source, document, input, maximum);
            Path userProfile = Files.createDirectories(work.resolve("profile/user"));
            Files.writeString(userProfile.resolve("registrymodifications.xcu"), """
                <oor:items xmlns:oor="http://openoffice.org/2001/registry">
                  <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
                    <prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>
                  </item>
                </oor:items>
                """);

            var command = new ProcessBuilder(configuration.executable().toString(),
                "-env:UserInstallation=" + userProfile.getParent().toUri().toASCIIString(),
                "--headless", "--norestore", "--convert-to", "pdf", "--outdir", work.toString(), document.toString())
                .directory(work.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            command.environment().put("TMP", work.toString());
            command.environment().put("TEMP", work.toString());
            command.environment().put("TMPDIR", work.toString());
            Process process = command.start();
            try {
                if (!process.waitFor(90, TimeUnit.SECONDS)) throw SystemFailure.LOCAL_OFFICE_TIMEOUT.fault();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw SystemFailure.LOCAL_OFFICE_UNAVAILABLE.fault(interrupted);
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }

            Path output = work.resolve("document.pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                || Files.size(output) == 0 || Files.size(output) > maximum) {
                throw SystemFailure.LOCAL_OFFICE_CONVERSION_FAILED.fault();
            }
            return Files.move(output, root.resolve("office-preview-" + UUID.randomUUID() + ".pdf"));
        } finally {
            // 仅清理本次新建的过程目录。转出的 PDF 由调用方完成存储后清理。
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { }
        }
    }

    /** LibreOffice 拒绝含 Windows 空目录记录的部分文档，只在转换副本中省略这些目录。 */
    private void copyInput(Path source, Path destination, String input, long maximum) throws IOException {
        if (Set.of("docx", "xlsx", "pptx").contains(input)) {
            try (var original = new ZipFile(source.toFile())) {
                var entries = original.stream().toList();
                if (entries.stream().anyMatch(LocalOfficePreview::windowsPlaceholder)) {
                    try (var output = new ZipOutputStream(Files.newOutputStream(destination))) {
                        byte[] block = new byte[65536];
                        long total = 0;
                        for (ZipEntry entry : entries) {
                            if (windowsPlaceholder(entry)) continue;
                            output.putNextEntry(new ZipEntry(entry.getName()));
                            try (var content = original.getInputStream(entry)) {
                                int read;
                                while ((read = content.read(block)) != -1) {
                                    total += read;
                                    if (total > maximum) throw SystemFailure.LOCAL_OFFICE_CONVERSION_FAILED.fault();
                                    output.write(block, 0, read);
                                }
                            }
                            output.closeEntry();
                        }
                    }
                    return;
                }
            } catch (ZipException failure) {
                throw SystemFailure.LOCAL_OFFICE_CONVERSION_FAILED.fault(failure);
            }
        }
        Files.copy(source, destination);
    }

    private static boolean windowsPlaceholder(ZipEntry entry) {
        return entry.isDirectory() && entry.getSize() == 0 && entry.getName().contains("\\");
    }

    /** 运维检查使用固定无害 DOCX，产物交给真实检测和阅读组件。 */
    public Path probe(Configuration configuration) throws IOException {
        Path root = PrivateFiles.directory(configuration.temporary().toString());
        Path sample = Files.createTempFile(root, "office-probe-", ".docx");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(sample))) {
                var entries = Map.of(
                    "[Content_Types].xml", """
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                        </Types>""",
                    "_rels/.rels", """
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                        </Relationships>""",
                    "word/document.xml", """
                        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                          <w:body><w:p><w:r><w:t>IMS preview check</w:t></w:r></w:p></w:body>
                        </w:document>""");
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return convert(configuration, sample, "docx", 1024 * 1024);
        } finally {
            Files.deleteIfExists(sample);
        }
    }
}
