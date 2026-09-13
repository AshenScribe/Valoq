package server.command;

public record BasicCommand(String username, String password) implements AuthCommand {}
