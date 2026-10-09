# ADR-001: Java 21, Spring Boot 4 and Maven
- Status: Accepted
- Date: 2026-10-08

## Context
The project must add a new, widely hired-for backend ecosystem to the author's profile (which is TypeScript/NestJS based) and provide mature tooling for transactions, concurrency, messaging and observability (spec §7, §8). The spec recommends Java 21 or Java 25 LTS.

The development machine has JDK 23 installed (not an LTS release). CI runners can install any JDK.

## Options considered
1. **Java 25 LTS** — newest LTS; includes JEP 491 (virtual threads no longer pin carrier threads inside `synchronized`). Requires installing JDK 25 locally.
2. **Java 21 LTS** — supported by every tool in the stack; JDK 23 can compile for it with `--release 21`.
3. **Go** — excellent for concurrency, but less coverage of the enterprise/Spring ecosystem the project is meant to demonstrate.

Build tool: **Maven** (most common in enterprise Java, declarative, simple multi-module support) vs **Gradle** (faster incremental builds, more flexible, more to learn).

## Decision
- Target **Java 21** (`maven.compiler.release=21`); build locally with JDK 23 and in CI with Temurin 21.
- **Spring Boot 4.1** (current stable line; Spring Framework 7, Jackson 3, JUnit 6).
- **Maven** multi-module build with the Maven Wrapper pinned to Maven 3.9.14.

## Consequences
- Records, sealed types, pattern matching for `switch` and virtual threads are all available.
- JEP 491 is not available: under virtual threads, `synchronized` blocks pin carrier threads. Avoid `synchronized` on hot request paths and prefer `java.util.concurrent` locks. The virtual-thread benchmark (W5-04) should note this.
- Upgrading to Java 25 later is a one-line change (`java.version`) plus a CI JDK bump, and would be recorded as a new ADR.
- Spring Boot 4 split auto-configuration into modules, so dependencies use the new starters (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, `spring-boot-starter-webmvc-test`, …) and test annotations moved packages.
