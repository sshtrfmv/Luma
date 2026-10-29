package ru.sasha.tvlauncher;

import java.util.*;
import java.util.regex.*;

/** Pure HDMI grouping/identification policy. No privileged CEC API or guessed ports. */
final class HdmiLogic {
  static final String[] TYPES = {"auto", "playstation", "xbox", "box", "pc", "other"};
  static final String[] TYPE_LABELS = {
    "Автоматически", "PlayStation", "Xbox", "ТВ-приставка", "Компьютер", "Другое устройство"
  };

  static final class Input {
    final String id, parent, label, custom;
    final int state;

    Input(String id, String parent, String label, String custom, int state) {
      this.id = id;
      this.parent = parent == null ? "" : parent;
      this.label = clean(label);
      this.custom = clean(custom);
      this.state = state;
    }

    String name() {
      return custom.isEmpty() ? label : custom;
    }
  }

  static final class Port {
    final String id, target, label, device, type;
    final int state;

    Port(Input root, Input chosen) {
      id = root.id;
      target = chosen.id;
      label = root.label.isEmpty() ? "HDMI" : root.label;
      device = chosen.name().equals(label) ? "" : chosen.name();
      type = detect(device.isEmpty() ? root.name() : device);
      state = root.state;
    }
  }

  static String clean(String s) {
    if (s == null) return "";
    String value = s.replaceAll("[\\p{Cntrl}]", " ").trim();
    return value.substring(0, Math.min(64, value.length()));
  }

  static String checkedType(String value) {
    return Arrays.asList(TYPES).contains(value) ? value : "auto";
  }

  static String detect(String name) {
    String s = clean(name).toLowerCase(Locale.ROOT);
    if (s.matches(".*(?:play\\s*station|\\bps[345]\\b).*")) return "playstation";
    if (s.contains("xbox")) return "xbox";
    if (s.matches(".*(?:\\bpc\\b|computer|компьютер|macbook|windows).*")) return "pc";
    if (s.matches(".*(?:apple\\s*tv|chromecast|fire\\s*tv|mi\\s*box|tv\\s*box|приставк).*"))
      return "box";
    return "other";
  }

  static String displayName(Port port, String manualName, String manualType) {
    String name = clean(manualName);
    if (!name.isEmpty()) return name;
    if (!port.device.isEmpty()) return port.device;
    String type = checkedType(manualType);
    if (!type.equals("auto")) return TYPE_LABELS[Arrays.asList(TYPES).indexOf(type)];
    return port.device.isEmpty() ? port.label : port.device;
  }

  static String iconType(Port port, String manualType) {
    String type = checkedType(manualType);
    return type.equals("auto") ? port.type : type;
  }

  static String stateLabel(int state) {
    switch (state) {
      case 0:
        return "Подключено";
      case 1:
        return "Режим ожидания";
      case 2:
        return "Не подключено";
      default:
        return "Состояние неизвестно";
    }
  }

  static int portNumber(String label) {
    Matcher m = Pattern.compile("(?i)\\bhdmi\\s*[-:]?\\s*(\\d{1,2})\\b").matcher(label);
    return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
  }

  static List<Port> group(List<Input> inputs) {
    Map<String, Input> map = new LinkedHashMap<>();
    for (Input input : inputs)
      if (input.id != null && !input.id.isEmpty() && map.size() < 64) map.put(input.id, input);
    Map<String, List<Input>> groups = new LinkedHashMap<>();
    for (Input input : map.values()) {
      Input root = input;
      Set<String> seen = new HashSet<>();
      while (!root.parent.isEmpty() && map.containsKey(root.parent) && seen.add(root.id))
        root = map.get(root.parent);
      if (!seen.add(root.id)) root = input;
      groups.computeIfAbsent(root.id, k -> new ArrayList<>()).add(input);
    }
    List<Port> result = new ArrayList<>();
    for (Map.Entry<String, List<Input>> group : groups.entrySet()) {
      Input root = map.get(group.getKey()), chosen = root;
      int score = score(root, root);
      for (Input candidate : group.getValue())
        if (score(candidate, root) > score) {
          chosen = candidate;
          score = score(candidate, root);
        }
      result.add(new Port(root, chosen));
    }
    result.sort(
        Comparator.comparingInt((Port p) -> portNumber(p.label))
            .thenComparing(p -> p.label)
            .thenComparing(p -> p.id));
    return Collections.unmodifiableList(result);
  }

  private static int score(Input input, Input root) {
    int state = input.state == 0 ? 100 : input.state == 1 ? 50 : 0;
    int known = input.state != 2 && !detect(input.name()).equals("other") ? 200 : 0;
    return state + known + (!input.id.equals(root.id) && !input.name().isEmpty() ? 5 : 0);
  }
}
