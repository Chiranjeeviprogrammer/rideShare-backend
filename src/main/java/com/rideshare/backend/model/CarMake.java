package com.rideshare.backend.model;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "car_makes")
public class CarMake {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private String logoUrl;

    @OneToMany(mappedBy = "make", cascade = CascadeType.ALL)
    private List<CarModel> models;

    public CarMake() {}
    public CarMake(String name, String logoUrl) {
        this.name = name;
        this.logoUrl = logoUrl;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public List<CarModel> getModels() { return models; }
    public void setModels(List<CarModel> models) { this.models = models; }
}
