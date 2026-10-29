package ru.sasha.tvlauncher;

import java.util.*;

public class HdmiLogicTest {
  static int checks;

  static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("HDMI " + checks);
  }

  static HdmiLogic.Input input(String id, String parent, String label, int state) {
    return new HdmiLogic.Input(id, parent, label, "", state);
  }

  public static void main(String[] args) {
    check(HdmiLogic.detect("PlayStation 5").equals("playstation"));
    check(HdmiLogic.detect("PS5-123").equals("playstation"));
    check(HdmiLogic.detect("PS4 Pro").equals("playstation"));
    check(HdmiLogic.detect("Xbox Series X").equals("xbox"));
    check(HdmiLogic.detect("My PC").equals("pc"));
    check(HdmiLogic.detect("Apple TV").equals("box"));
    check(HdmiLogic.detect("HDMI 1").equals("other"));
    check(HdmiLogic.detect("EPS500").equals("other"));
    check(HdmiLogic.checkedType("invalid").equals("auto"));
    check(HdmiLogic.checkedType("playstation").equals("playstation"));
    check(HdmiLogic.clean(null).equals(""));
    check(HdmiLogic.clean(" test\nname ").equals("test name"));
    check(HdmiLogic.clean(String.join("", Collections.nCopies(100, "x"))).length() == 64);
    List<HdmiLogic.Port> ports =
        HdmiLogic.group(
            Arrays.asList(
                input("root10", "", "HDMI 10", 0),
                input("root2", "", "HDMI 2", 2),
                input("root1", "", "HDMI 1", 0),
                input("ps", "root1", "PlayStation 5", 0)));
    check(ports.size() == 3);
    check(ports.get(0).id.equals("root1"));
    check(ports.get(1).id.equals("root2"));
    check(ports.get(2).id.equals("root10"));
    HdmiLogic.Port ps = ports.get(0);
    check(ps.target.equals("ps"));
    check(ps.type.equals("playstation"));
    check(ps.label.equals("HDMI 1"));
    check(ps.device.equals("PlayStation 5"));
    check(ps.state == 0);
    check(HdmiLogic.displayName(ps, "", "auto").equals("PlayStation 5"));
    check(HdmiLogic.displayName(ps, "Моя консоль", "auto").equals("Моя консоль"));
    check(HdmiLogic.displayName(ps, "", "xbox").equals("PlayStation 5"));
    check(HdmiLogic.iconType(ps, "auto").equals("playstation"));
    check(HdmiLogic.iconType(ps, "pc").equals("pc"));
    check(HdmiLogic.group(Collections.emptyList()).isEmpty());
    check(
        HdmiLogic.group(Arrays.asList(input("a", "", "HDMI 1", 0), input("a", "", "HDMI 1", 0)))
                .size()
            == 1);
    check(
        HdmiLogic.group(Arrays.asList(input("a", "b", "A", 0), input("b", "a", "B", 0))).size()
            == 2);
    check(
        HdmiLogic.group(Collections.singletonList(input("child", "missing", "PS5", 0)))
            .get(0)
            .id
            .equals("child"));
    HdmiLogic.Port standby =
        HdmiLogic.group(
                Arrays.asList(input("root", "", "HDMI 1", 0), input("ps", "root", "PS5", 1)))
            .get(0);
    check(standby.type.equals("playstation"));
    check(standby.state == 0);
    HdmiLogic.Port unplugged =
        HdmiLogic.group(
                Arrays.asList(input("root", "", "HDMI 1", 0), input("old", "root", "PS5", 2)))
            .get(0);
    check(unplugged.target.equals("root"));
    check(HdmiLogic.stateLabel(0).equals("Подключено"));
    check(HdmiLogic.stateLabel(1).equals("Режим ожидания"));
    check(HdmiLogic.stateLabel(2).equals("Не подключено"));
    check(HdmiLogic.stateLabel(-1).equals("Состояние неизвестно"));
    check(LauncherLogic.drawerKind("devices").equals("devices"));
    check(LauncherLogic.defaultFocusTag("devices").equals("button:Устройства"));
    List<HdmiLogic.Input> many = new ArrayList<>();
    for (int i = 0; i < 100; i++) many.add(input("p" + i, "", "HDMI " + i, 0));
    check(HdmiLogic.group(many).size() == 64);
    boolean immutable = false;
    try {
      ports.clear();
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    check(immutable);
    System.out.println("PASS: HDMI " + checks + " checks");
  }
}
