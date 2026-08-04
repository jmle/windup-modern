package com.example.demo;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A JPA entity demonstrating javax.persistence usage that should be
 * flagged for migration to jakarta.persistence.
 */
@Entity
@Table(name = "hello_service")
public class HelloService {

    @Id
    private Long id;

    private String message;

    public HelloService() {
    }

    public HelloService(Long id, String message) {
        this.id = id;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
