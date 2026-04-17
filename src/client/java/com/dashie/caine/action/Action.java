package com.dashie.caine.action;

public sealed interface Action {
    record Chat(String message) implements Action {}
    record Command(String command) implements Action {}
    record TpToPlayer(String player) implements Action {}
    record LookAtPlayer(String player) implements Action {}
    record Pathfind(int x, int y, int z) implements Action {}
    record FollowPlayer(String player) implements Action {}
    record Mine(String block, int quantity) implements Action {}
    record GiveItem(String player, String item, int count, boolean drop) implements Action {}
    record SaveMemory(String category, String subject, String content, int importance) implements Action {}
    record ForgetMemory(String subject, String category) implements Action {}
    record RecallMemory(String query) implements Action {}
    record StopTask() implements Action {}
    record Observe(int seconds) implements Action {}
    record Delay(int seconds) implements Action {}
    record Wait(int seconds) implements Action {}
    record Nothing() implements Action {}
    record UseItemOnBlock(int x, int y, int z) implements Action {}
    record UseItemOnEntity(String target) implements Action {}
    record SelectSlot(int slot) implements Action {}
    record Attack(String target) implements Action {}
    record BackupInventory(String player) implements Action {}
    record RestoreInventory(String player) implements Action {}
    record RunScript(java.util.List<String> commands, int delayTicks, int repeat, String stopCondition) implements Action {}
    record LearnSkill(String name, String description, java.util.List<String> commands,
                      java.util.List<String> triggerPhrases) implements Action {}
    record UseSkill(String name, String context) implements Action {}
    record ImproveSkill(String name, String description, java.util.List<String> commands) implements Action {}
    record ForgetSkill(String name) implements Action {}
    record ListSkills() implements Action {}
    record BuildStructure(String description, int width, int height, int depth,
                          String style, String player) implements Action {}
    record DownloadSchematic(String url, String name) implements Action {}
    record PlaceSchematic(String name, int x, int y, int z, String player) implements Action {}
    record ListSchematics() implements Action {}
    record UndoBuild() implements Action {}
    record ScanTerrain(int radius) implements Action {}
}
