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
    record StopTask() implements Action {}
    record Observe(int seconds) implements Action {}
    record Delay(int seconds) implements Action {}
    record Wait(int seconds) implements Action {}
    record Nothing() implements Action {}
}
