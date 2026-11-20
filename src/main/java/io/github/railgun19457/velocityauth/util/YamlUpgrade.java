package io.github.railgun19457.velocityauth.util;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** 按键路径升级用户 YAML，在写盘前提供候选内容供各加载器校验。 */
public final class YamlUpgrade {

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]*:[ \\t]*\\d+[ \\t]*(?=\\r?$)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private YamlUpgrade() {
    }

    public static Prepared prepare(Class<?> anchor, String resource, Path target, BiConsumer<Integer, Editor> upgrade)
            throws IOException {
        String builtin;
        try (var in = anchor.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("JAR 内缺少资源 " + resource);
            }
            builtin = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path file = Files.isSymbolicLink(target) ? target.toRealPath() : target.toAbsolutePath();
        String original = Files.exists(file) ? Files.readString(file) : null;
        if (original == null) {
            return new Prepared(file, null, builtin, List.of());
        }
        boolean bom = original.startsWith("\uFEFF");
        String document = (bom ? original.substring(1) : original).replace("\r\n", "\n");
        int version = ReleasedResource.versionOf(document.getBytes(StandardCharsets.UTF_8));
        int latest = ReleasedResource.versionOf(builtin.getBytes(StandardCharsets.UTF_8));
        if (version >= latest) {
            return new Prepared(file, original, original, List.of());
        }

        LoaderOptions loader = new LoaderOptions().setProcessComments(true);
        loader.setAllowDuplicateKeys(false);
        DumperOptions dumper = new DumperOptions();
        dumper.setProcessComments(true);
        dumper.setSplitLines(false);
        Yaml yaml = new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
        // 先用现有安全构造器检查重复键及别名结构，再操作可保留注释的节点树。
        yaml.load(document);
        // SnakeYAML 把文件头注释挂在第一个键上。独立处理文件头，避免移动该键时一起丢失。
        int headerEnd = headerEnd(document);
        String header = document.substring(0, headerEnd);
        String body = document.substring(headerEnd);
        var versionMarker = VERSION_LINE.matcher(header);
        String versionLine = "# version: " + latest;
        boolean markerInHeader = versionMarker.find();
        if (version >= 0 && markerInHeader) {
            header = versionMarker.replaceFirst(versionLine);
        } else {
            header = versionLine + "\n" + (markerInHeader ? versionMarker.replaceFirst("") : header);
            if (version >= 0) {
                body = VERSION_LINE.matcher(body).replaceFirst("");
            }
        }
        if (!header.endsWith("\n")) {
            header += "\n";
        }
        Editor editor = new Editor(mapping(yaml.compose(new StringReader(body))),
                mapping(yaml.compose(new StringReader(builtin.substring(headerEnd(builtin))))));
        upgrade.accept(version, editor);

        StringWriter output = new StringWriter();
        yaml.serialize(editor.root, output);
        String rendered = header + output;
        if (original.contains("\r\n")) {
            rendered = rendered.replace("\n", "\r\n");
        }
        if (bom) {
            rendered = "\uFEFF" + rendered;
        }
        return new Prepared(file, original, rendered, List.copyOf(editor.notices));
    }

    private static int headerEnd(String text) {
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            end = end < 0 ? text.length() : end;
            String line = text.substring(start, end).stripLeading();
            if (!line.isBlank() && !line.startsWith("#")) {
                break;
            }
            start = Math.min(text.length(), end + 1);
        }
        return start;
    }

    public record Prepared(Path file, String original, String content, List<String> notices) {

        public void save(Logger logger) throws IOException {
            if (Objects.equals(original, content)) {
                return;
            }
            Path parent = file.getParent();
            Files.createDirectories(parent);
            Path staged = Files.createTempFile(parent, file.getFileName() + ".", ".tmp");
            Path backup = null;
            try {
                if (original != null) {
                    Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                Files.writeString(staged, content);
                String current = Files.exists(file) ? Files.readString(file) : null;
                if (!Objects.equals(original, current)) {
                    throw new IOException(file.getFileName() + " 在升级过程中被修改，请重新加载");
                }
                if (original != null) {
                    String prefix = file.getFileName() + ".bak-" + LocalDateTime.now().format(STAMP) + "-";
                    backup = Files.createTempFile(parent, prefix, "");
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    if (!original.equals(Files.readString(backup)) || !original.equals(Files.readString(file))) {
                        throw new IOException(file.getFileName() + " 在备份过程中被修改，请重新加载");
                    }
                }
                Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
            if (logger != null) {
                if (backup == null) {
                    logger.info("[VelocityAuth] 已释放 {}", file.getFileName());
                } else {
                    logger.info("[VelocityAuth] 已按键升级 {}，原文件备份为 {}", file.getFileName(), backup.getFileName());
                }
                notices.forEach(notice -> logger.warn("[VelocityAuth] {}：{}", file.getFileName(), notice));
            }
        }
    }

    public static final class Editor {
        private MappingNode root;
        private final MappingNode defaults;
        private final List<String> notices = new ArrayList<>();

        private Editor(MappingNode root, MappingNode defaults) {
            this.root = root;
            this.defaults = defaults;
        }

        public boolean has(String path) {
            return find(root, path.split("\\.")) != null;
        }

        public String keyIgnoringCase(String parent, String key) {
            NodeTuple node = find(root, parent.split("\\."));
            if (node != null && node.getValueNode() instanceof MappingNode map) {
                var names = entries(map).keySet();
                if (!names.contains(key)) {
                    return names.stream().filter(key::equalsIgnoreCase).findFirst().orElse(key);
                }
            }
            return key;
        }

        public void move(String from, String to) {
            move(from.split("\\."), to.split("\\."));
        }

        public void moveLanguage(String from, String to) {
            List<String[]> sources = languagePaths(root, from);
            List<String[]> targets = languagePaths(root, to);
            if (!sources.isEmpty()) {
                move(sources.getLast(), targets.isEmpty() ? to.split("\\.") : targets.getLast());
                removeLanguageKey(from);
            }
        }

        private void move(String[] fromPath, String[] path) {
            NodeTuple source = find(root, fromPath);
            if (source == null) {
                return;
            }
            String to = String.join(".", path);
            String from = String.join(".", fromPath);
            root = edit(root, path, 0, existing -> {
                if (existing == null) {
                    return new NodeTuple(rename((ScalarNode) source.getKeyNode(), path[path.length - 1]),
                            source.getValueNode());
                }
                if (existing.getValueNode() instanceof MappingNode target
                        && source.getValueNode() instanceof MappingNode legacy) {
                    return withValue(existing, merge(target, legacy, to + ".", key -> true, Set.of(), notices));
                }
                if (!sameScalar(existing.getValueNode(), source.getValueNode())) {
                    notices.add("保留 " + to + "，冲突的旧键 " + from + " 已归档到备份");
                }
                return existing;
            });
            if (find(root, path) == null) {
                notices.add("保留 " + to + " 的父节点，无法迁入的旧键 " + from + " 已归档到备份");
            }
            root = edit(root, fromPath, 0, existing -> null);
        }

        public void remove(String path) {
            MappingNode updated = edit(root, path.split("\\."), 0, existing -> null);
            if (updated != root) {
                notices.add("移除废弃键 " + path);
                root = updated;
            }
        }

        public void removeLanguageKey(String path) {
            List<String[]> nodes = languagePaths(root, path);
            for (String[] node : nodes) {
                root = edit(root, node, 0, existing -> null);
            }
            if (!nodes.isEmpty()) {
                notices.add("移除废弃键 " + path);
            }
        }

        public void fill(Predicate<String> addMissing) {
            root = merge(root, defaults, "", addMissing, Set.of(), null);
        }

        /** 旧模板只需记录发生过变化且版本可确定的默认文案。 */
        public void mergeLanguage(Map<String, String> previousDefaults) {
            previousDefaults.forEach((path, previous) -> {
                NodeTuple replacement = find(defaults, path.split("\\."));
                List<String[]> nodes = languagePaths(root, path);
                if (!nodes.isEmpty() && replacement != null
                        && replacement.getValueNode() instanceof ScalarNode value) {
                    root = edit(root, nodes.getLast(), 0, existing -> {
                        if (existing.getValueNode() instanceof ScalarNode current && Tag.STR.equals(current.getTag())
                                && current.getValue().equals(previous) && !sameScalar(current, value)) {
                            return withValue(existing, copyComments(current, new ScalarNode(value.getTag(),
                                    value.getValue(), null, null, value.getScalarStyle())));
                        }
                        return existing;
                    });
                }
            });
            Set<String> flatKeys = new LinkedHashSet<>();
            collectDottedKeys(root, "", flatKeys, new LinkedHashSet<>());
            root = merge(root, defaults, "", key -> true, flatKeys, null);
        }
    }

    private static MappingNode merge(MappingNode user, MappingNode template, String prefix,
                                     Predicate<String> addMissing, Set<String> dottedKeys, List<String> conflicts) {
        var entries = entries(user);
        boolean changed = false;
        for (var entry : entries(template).entrySet()) {
            String key = entry.getKey();
            String path = prefix + key;
            NodeTuple source = entry.getValue();
            NodeTuple target = entries.get(key);
            NodeTuple next = target;
            if (target == null && addMissing.test(path) && !dottedKeys.contains(path)) {
                next = source;
                if (source.getValueNode() instanceof MappingNode children) {
                    next = withValue(source, merge(copyMapping(children, List.of()), children,
                            path + ".", addMissing, dottedKeys, conflicts));
                }
            } else if (target != null) {
                Node current = target.getValueNode();
                Node value = source.getValueNode();
                if (current instanceof MappingNode currentMap && value instanceof MappingNode templateMap) {
                    next = withValue(target, merge(currentMap, templateMap, path + ".",
                            addMissing, dottedKeys, conflicts));
                } else if (conflicts != null && !sameScalar(current, value)) {
                    conflicts.add("保留 " + path + "，冲突的旧值已归档到备份");
                }
            }
            if (next != target) {
                entries.put(key, next);
                changed = true;
            }
        }
        return changed ? copyMapping(user, new ArrayList<>(entries.values())) : user;
    }

    private static NodeTuple find(MappingNode root, String[] path) {
        Node current = root;
        NodeTuple tuple = null;
        for (String key : path) {
            if (!(current instanceof MappingNode map) || (tuple = entries(map).get(key)) == null) {
                return null;
            }
            current = tuple.getValueNode();
        }
        return tuple;
    }

    private static MappingNode edit(MappingNode map, String[] path, int index, UnaryOperator<NodeTuple> change) {
        var entries = entries(map);
        String key = path[index];
        NodeTuple current = entries.get(key);
        NodeTuple next;
        if (index == path.length - 1) {
            next = change.apply(current);
        } else if (current == null) {
            MappingNode empty = mapping(null);
            MappingNode created = edit(empty, path, index + 1, change);
            next = created == empty ? null : new NodeTuple(rename(null, key), created);
        } else if (current.getValueNode() instanceof MappingNode child) {
            next = withValue(current, edit(child, path, index + 1, change));
        } else {
            return map;
        }
        if (current == next) {
            return map;
        }
        if (next == null) {
            entries.remove(key);
        } else {
            entries.put(key, next);
        }
        return copyMapping(map, new ArrayList<>(entries.values()));
    }

    private static LinkedHashMap<String, NodeTuple> entries(MappingNode map) {
        var entries = new LinkedHashMap<String, NodeTuple>();
        for (NodeTuple entry : map.getValue()) {
            Node key = entry.getKeyNode();
            if (Tag.MERGE.equals(key.getTag())) {
                Node value = entry.getValueNode();
                if (value instanceof SequenceNode sequence) {
                    for (Node inherited : sequence.getValue()) {
                        entries((MappingNode) inherited).forEach(entries::putIfAbsent);
                    }
                } else {
                    entries((MappingNode) value).forEach(entries::putIfAbsent);
                }
            } else if (key instanceof ScalarNode keyNode && Tag.STR.equals(key.getTag())) {
                entries.put(keyNode.getValue(), entry);
            } else {
                throw new YAMLException("YAML 配置键必须是字符串");
            }
        }
        return entries;
    }

    private static void collectDottedKeys(MappingNode map, String prefix, Set<String> keys, Set<MappingNode> ancestors) {
        if (!ancestors.add(map)) {
            throw new YAMLException("语言文件含循环引用");
        }
        entries(map).forEach((key, entry) -> {
            String path = prefix + key;
            if (key.contains(".")) {
                keys.add(path);
            }
            if (entry.getValueNode() instanceof MappingNode children) {
                collectDottedKeys(children, path + ".", keys, ancestors);
            }
        });
        ancestors.remove(map);
    }

    private static List<String[]> languagePaths(MappingNode root, String keyPath) {
        List<String[]> paths = new ArrayList<>();
        collectLanguagePaths(root, keyPath, new ArrayList<>(), paths);
        return paths;
    }

    private static void collectLanguagePaths(MappingNode map, String remaining, List<String> parents,
                                             List<String[]> paths) {
        entries(map).forEach((key, entry) -> {
            parents.add(key);
            if (remaining.equals(key)) {
                paths.add(parents.toArray(String[]::new));
            } else if (remaining.startsWith(key + ".") && entry.getValueNode() instanceof MappingNode child) {
                collectLanguagePaths(child, remaining.substring(key.length() + 1), parents, paths);
            }
            parents.removeLast();
        });
    }

    private static boolean sameScalar(Node first, Node second) {
        return first instanceof ScalarNode a && second instanceof ScalarNode b
                && a.getTag().equals(b.getTag()) && a.getValue().equals(b.getValue());
    }

    private static MappingNode mapping(Node node) {
        if (node instanceof MappingNode map && Tag.MAP.equals(map.getTag())) {
            return map;
        }
        MappingNode empty = new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
        if (node == null) {
            return empty;
        }
        if (Tag.NULL.equals(node.getTag()) || Tag.COMMENT.equals(node.getTag())) {
            return copyComments(node, empty);
        }
        throw new YAMLException("YAML 根节点必须是映射");
    }

    private static MappingNode copyMapping(MappingNode map, List<NodeTuple> entries) {
        return copyComments(map, new MappingNode(Tag.MAP, new ArrayList<>(entries), map.getFlowStyle()));
    }

    private static ScalarNode rename(ScalarNode source, String name) {
        if (source == null) {
            return new ScalarNode(Tag.STR, name, null, null, DumperOptions.ScalarStyle.PLAIN);
        }
        return source.getValue().equals(name) ? source
                : copyComments(source, new ScalarNode(Tag.STR, name, null, null, source.getScalarStyle()));
    }

    private static NodeTuple withValue(NodeTuple entry, Node value) {
        return entry.getValueNode() == value ? entry : new NodeTuple(entry.getKeyNode(), value);
    }

    private static <T extends Node> T copyComments(Node from, T to) {
        to.setBlockComments(from.getBlockComments());
        to.setInLineComments(from.getInLineComments());
        to.setEndComments(from.getEndComments());
        return to;
    }
}
