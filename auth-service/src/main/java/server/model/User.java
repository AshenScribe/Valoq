package server.model;

public record User(String userId, String username, String passwordHash, String salt, String email) {}
