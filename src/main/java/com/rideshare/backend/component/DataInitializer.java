package com.rideshare.backend.component;

import com.rideshare.backend.model.CarMake;
import com.rideshare.backend.model.CarModel;
import com.rideshare.backend.model.User;
import com.rideshare.backend.model.Vehicle;
import com.rideshare.backend.repository.CarMakeRepository;
import com.rideshare.backend.repository.CarModelRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(10)
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private CarMakeRepository carMakeRepository;

    @Autowired
    private CarModelRepository carModelRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private UserRepository userRepository;

    private void addBrand(String name, String domain, String[] models) {
        String logoUrl = "https://logo.clearbit.com/" + domain;
        CarMake make = carMakeRepository.findAll().stream()
                .filter(existing -> name.equalsIgnoreCase(existing.getName()))
                .findFirst()
                .orElseGet(() -> carMakeRepository.save(new CarMake(name, logoUrl)));

        List<CarModel> existingModels = carModelRepository.findByMakeId(make.getId());
        List<CarModel> newModels = new ArrayList<>();
        for (String modelName : models) {
            boolean exists = existingModels.stream()
                    .anyMatch(existing -> modelName.equalsIgnoreCase(existing.getName()));
            if (!exists) {
                String formattedName = modelName.replace(" ", "+");
                String carImageUrl = "https://placehold.co/400x200/000000/FFFFFF/png?text=" + formattedName;
                newModels.add(new CarModel(modelName, carImageUrl, make));
            }
        }
        if (!newModels.isEmpty()) {
            carModelRepository.saveAll(newModels);
        }
    }

    @Override
    public void run(String... args) throws Exception {
        if (carMakeRepository.count() < 30 || carModelRepository.count() < 115) {
            System.out.println("Inserting 30 Car Brands and Models...");

            addBrand("Tata", "tatamotors.com", new String[]{"Nexon", "Punch", "Harrier", "Safari", "Tiago", "Altroz"});
            addBrand("Mahindra", "mahindra.com", new String[]{"Thar", "XUV700", "Scorpio-N", "Bolero", "XUV300"});
            addBrand("Maruti Suzuki", "marutisuzuki.com", new String[]{"Swift", "Baleno", "Brezza", "Grand Vitara", "Ertiga", "Fronx"});
            addBrand("Hyundai", "hyundai.com", new String[]{"Creta", "Venue", "Verna", "i20", "Tucson", "Ioniq 5"});
            addBrand("Kia", "kia.com", new String[]{"Seltos", "Sonet", "Carens", "EV6"});
            addBrand("Toyota", "toyota.com", new String[]{"Fortuner", "Innova Crysta", "Urban Cruiser", "Camry", "Hilux"});
            addBrand("Honda", "hondacarindia.com", new String[]{"City", "Amaze", "Civic"});
            addBrand("MG", "mgmotor.co.in", new String[]{"Hector", "Astor", "Comet EV", "Gloster"});
            addBrand("Volkswagen", "volkswagen.com", new String[]{"Virtus", "Taigun", "Tiguan", "Polo"});
            addBrand("Skoda", "skoda-auto.com", new String[]{"Slavia", "Kushaq", "Kodiaq", "Octavia"});
            addBrand("Nissan", "nissan-global.com", new String[]{"Magnite", "Kicks", "GTR"});
            addBrand("Renault", "renault.com", new String[]{"Kiger", "Kwid", "Triber"});
            addBrand("Jeep", "jeep.com", new String[]{"Compass", "Wrangler", "Meridian", "Grand Cherokee"});
            addBrand("Ford", "ford.com", new String[]{"Endeavour", "EcoSport", "Mustang"});
            addBrand("Chevrolet", "chevrolet.com", new String[]{"Cruze", "Beat", "Camaro"});
            addBrand("Tesla", "tesla.com", new String[]{"Model 3", "Model S", "Model X", "Model Y", "Cybertruck"});
            addBrand("BMW", "bmw.com", new String[]{"X1", "X5", "3 Series", "5 Series", "Z4"});
            addBrand("Mercedes-Benz", "mercedes-benz.com", new String[]{"C-Class", "E-Class", "GLC", "S-Class", "G-Wagon"});
            addBrand("Audi", "audi.com", new String[]{"A4", "A6", "Q5", "Q7", "e-tron"});
            addBrand("Volvo", "volvocars.com", new String[]{"XC40", "XC60", "XC90"});
            addBrand("Jaguar", "jaguar.com", new String[]{"F-Pace", "XF", "F-Type"});
            addBrand("Land Rover", "landrover.com", new String[]{"Range Rover", "Defender", "Discovery"});
            addBrand("Porsche", "porsche.com", new String[]{"Macan", "Cayenne", "911", "Taycan"});
            addBrand("Lexus", "lexus.com", new String[]{"NX", "RX", "LX"});
            addBrand("BYD", "byd.com", new String[]{"Atto 3", "Seal", "E6"});
            addBrand("Fiat", "fiat.com", new String[]{"Linea", "Punto", "500"});
            addBrand("Mitsubishi", "mitsubishi-motors.com", new String[]{"Pajero Sport", "Outlander"});
            addBrand("Citroen", "citroen.com", new String[]{"C3", "C5 Aircross", "Basalt"});
            addBrand("Mini", "mini.com", new String[]{"Cooper", "Countryman"});
            addBrand("Aston Martin", "astonmartin.com", new String[]{"DBX", "Vantage", "DB11"});

            System.out.println("Finished inserting 30 Car Brands and Models! Total models: " + carModelRepository.count());
        }

        seedVehicleFixtures();
    }

    private void seedVehicleFixtures() {
        if (vehicleRepository.count() >= 88) {
            return;
        }

        User driver = userRepository.findByEmail("driver@rideshare.com");
        if (driver == null) {
            driver = userRepository.findAll().stream()
                    .filter(u -> "DRIVER".equalsIgnoreCase(u.getRole()))
                    .findFirst()
                    .orElse(null);
        }
        if (driver == null) {
            driver = new User();
            driver.setName("Test Driver");
            driver.setEmail("driver@rideshare.com");
            driver.setPhoneNumber("+919876543212");
            driver.setRole("DRIVER");
            driver.setStatus("ACTIVE");
            driver.setVerified(true);
            driver.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
            driver.setCreatedAt(LocalDateTime.now());
            driver = userRepository.save(driver);
        }

        List<CarModel> allModels = carModelRepository.findAll();
        long currentCount = vehicleRepository.count();
        int needed = (int) (88 - currentCount);
        String[] colors = {"White", "Silver", "Grey", "Black", "Blue", "Red", "Brown"};
        String[] years = {"2020", "2021", "2022", "2023", "2024"};
        List<Vehicle> newVehicles = new ArrayList<>();

        for (int i = 0; i < needed && !allModels.isEmpty(); i++) {
            CarModel m = allModels.get(i % allModels.size());
            Vehicle v = new Vehicle();
            v.setMake(m.getMake() != null ? m.getMake().getName() : "Tata");
            v.setModel(m.getName());
            v.setYear(years[i % years.length]);
            v.setColor(colors[i % colors.length]);
            v.setLicensePlate(String.format("KA 01 RS %04d", (int) (currentCount + i + 100)));
            v.setImageUrl(m.getImageUrl() != null ? m.getImageUrl() : "https://images.unsplash.com/photo-1549317661-bd32c8ce0db2?w=200");
            v.setDriver(driver);
            newVehicles.add(v);
        }
        if (!newVehicles.isEmpty()) {
            vehicleRepository.saveAll(newVehicles);
            System.out.println("Seeded vehicle fixtures: " + vehicleRepository.count() + " vehicles now available.");
        }
    }
}
