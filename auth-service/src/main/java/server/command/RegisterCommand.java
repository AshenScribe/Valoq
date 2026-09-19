package server.command;

public record RegisterCommand(String username, String password, String salt, String email) implements AuthCommand {}
