package dev.agente.core.port;

@FunctionalInterface
public interface IdGenerator {

    String next();
}
