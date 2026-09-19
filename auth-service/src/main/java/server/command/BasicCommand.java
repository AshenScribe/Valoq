package server.command;

public record BasicCommand(String username, String password, String salt) implements AuthCommand {}
