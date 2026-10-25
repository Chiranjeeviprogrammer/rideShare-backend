package com.rideshare.backend.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "car_models")
public class CarModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private String imageUrl;

    @ManyToOne
    @JoinColumn(name = "make_id")
    @JsonIgnore
    private CarMake make;

    public CarModel() {}
    public CarModel(String name, String imageUrl, CarMake make) {
        this.name = name;
        this.imageUrl = imageUrl;
        this.make = make;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public CarMake getMake() { return make; }
    public void setMake(CarMake make) { this.make = make; }
}
