package com.medibridge.model;

import java.io.Serializable;

public record Medicine(int id, String name, String strength, String form) implements Serializable {
    public String label() {
        return name + " " + strength + " " + form;
    }
}
