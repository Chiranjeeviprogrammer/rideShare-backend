package com.rideshare.backend.config;

import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.time.LocalDateTime;

@Configuration
public class DummyDataSeeder {

    @Bean
    @Order(2)
    CommandLineRunner initDatabase(
            UserRepository userRepository,
            VehicleRepository vehicleRepository,
            RideRepository rideRepository,
            WalletRepository walletRepository,
            CarMakeRepository carMakeRepository,
            CarModelRepository carModelRepository
    ) {
        return args -> {
            try {
                if (userRepository.count() == 0) {
                    // Add dummy users
                    User rider1 = new User();
                    rider1.setName("Rider Test");
                    rider1.setEmail("rider@test.com");
                    rider1.setPassword("password123");
                    rider1.setRole("RIDER");
                    rider1.setPhoneNumber("1112223333");
                    rider1.setAvatarUrl("https://i.pravatar.cc/150?u=rider@test.com");
                    userRepository.save(rider1);

                    User driver1 = new User();
                    driver1.setName("Driver Test");
                    driver1.setEmail("driver@test.com");
                    driver1.setPassword("password123");
                    driver1.setRole("DRIVER");
                    driver1.setPhoneNumber("9998887777");
                    driver1.setAvatarUrl("https://i.pravatar.cc/150?u=driver@test.com");
                    userRepository.save(driver1);

                    // Add Wallets
                    Wallet riderWallet = new Wallet();
                    riderWallet.setUser(rider1);
                    riderWallet.setBalance(500.0);
                    walletRepository.save(riderWallet);

                    Wallet driverWallet = new Wallet();
                    driverWallet.setUser(driver1);
                    driverWallet.setBalance(150.0);
                    walletRepository.save(driverWallet);

                    // Add Vehicle
                    Vehicle vehicle1 = new Vehicle();
                    vehicle1.setMake("Toyota");
                    vehicle1.setModel("Camry");
                    vehicle1.setYear("2020");
                    vehicle1.setColor("White");
                    vehicle1.setLicensePlate("XYZ-1234");
                    vehicle1.setImageUrl("https://images.unsplash.com/photo-1550355291-bbee04a92027?q=80&w=200&auto=format&fit=crop");
                    vehicle1.setDriver(driver1);
                    vehicleRepository.save(vehicle1);

                    // Add Car Makes and Models
                    CarMake toyota = new CarMake("Toyota", "https://logo.clearbit.com/toyota.com");
                    carMakeRepository.save(toyota);
                    
                    CarModel camry = new CarModel();
                    camry.setName("Camry");
                    camry.setMake(toyota);
                    carModelRepository.save(camry);

                    CarMake honda = new CarMake("Honda", "https://logo.clearbit.com/honda.com");
                    carMakeRepository.save(honda);

                    CarModel civic = new CarModel();
                    civic.setName("Civic");
                    civic.setMake(honda);
                    carModelRepository.save(civic);

                    // Add a dummy ride (COMPLETED, so driver1 has no active ride conflicts)
                    Ride ride = new Ride();
                    ride.setRider(rider1);
                    ride.setDriver(driver1);
                    ride.setPickupLocation("Airport Terminal 1");
                    ride.setDropoffLocation("City Center Hotel");
                    ride.setStatus("COMPLETED");
                    ride.setFare(35.50);
                    ride.setRequestedAt(LocalDateTime.now().minusDays(1));
                    rideRepository.save(ride);
                    
                    System.out.println("Dummy Data Initialized successfully.");
                }
                
                if (rideRepository.count() < 5) {
                    User rider = userRepository.findByEmail("rider@test.com");
                    if (rider == null) {
                        rider = userRepository.findAll().stream().findFirst().orElse(null);
                    }
                    
                    if (rider != null) {
                        // Driver 2 - Ramesh Kumar
                        User driver2 = userRepository.findByEmail("ramesh@test.com");
                        if (driver2 == null) {
                            driver2 = new User();
                            driver2.setName("Ramesh Kumar");
                            driver2.setEmail("ramesh@test.com");
                            driver2.setPassword("password123");
                            driver2.setRole("DRIVER");
                            driver2.setPhoneNumber("9991112222");
                            driver2.setAvatarUrl("https://i.pravatar.cc/150?u=ramesh");
                            userRepository.save(driver2);
                        }
                        
                        final Long driver2Id = driver2.getId();
                        if (vehicleRepository.findAll().stream().noneMatch(v -> v.getDriver() != null && v.getDriver().getId().equals(driver2Id))) {
                            Vehicle vehicle2 = new Vehicle();
                            vehicle2.setMake("Maruti");
                            vehicle2.setModel("Swift");
                            vehicle2.setYear("2021");
                            vehicle2.setColor("Red");
                            vehicle2.setLicensePlate("DL 1C 1234");
                            vehicle2.setImageUrl("https://images.unsplash.com/photo-1549317661-bd32c8ce0db2?w=200");
                            vehicle2.setDriver(driver2);
                            vehicleRepository.save(vehicle2);
                        }
                        
                        Ride ride2 = new Ride();
                        ride2.setRider(rider);
                        ride2.setDriver(driver2);
                        ride2.setPickupLocation("Connaught Place, New Delhi");
                        ride2.setDropoffLocation("Cyber Hub, Gurgaon");
                        ride2.setStatus("PUBLISHED");
                        ride2.setAvailableSeats(3);
                        ride2.setTotalSeats(4);
                        ride2.setDepartureTime(LocalDateTime.now().plusHours(1));
                        ride2.setFare(250.00);
                        ride2.setRequestedAt(LocalDateTime.now().minusMinutes(10));
                        rideRepository.save(ride2);
                        
                        // Driver 3 - Suresh Singh
                        User driver3 = userRepository.findByEmail("suresh@test.com");
                        if (driver3 == null) {
                            driver3 = new User();
                            driver3.setName("Suresh Singh");
                            driver3.setEmail("suresh@test.com");
                            driver3.setPassword("password123");
                            driver3.setRole("DRIVER");
                            driver3.setPhoneNumber("9993334444");
                            driver3.setAvatarUrl("https://i.pravatar.cc/150?u=suresh");
                            userRepository.save(driver3);
                        }
                        
                        final Long driver3Id = driver3.getId();
                        if (vehicleRepository.findAll().stream().noneMatch(v -> v.getDriver() != null && v.getDriver().getId().equals(driver3Id))) {
                            Vehicle vehicle3 = new Vehicle();
                            vehicle3.setMake("Hyundai");
                            vehicle3.setModel("i20");
                            vehicle3.setYear("2022");
                            vehicle3.setColor("Silver");
                            vehicle3.setLicensePlate("HR 26 DQ 5566");
                            vehicle3.setImageUrl("https://images.unsplash.com/photo-1605559424843-9e4c228bf1c2?w=200");
                            vehicle3.setDriver(driver3);
                            vehicleRepository.save(vehicle3);
                        }
                        
                        Ride ride3 = new Ride();
                        ride3.setRider(rider);
                        ride3.setDriver(driver3);
                        ride3.setPickupLocation("Noida Sector 18, Noida");
                        ride3.setDropoffLocation("India Gate, Delhi");
                        ride3.setStatus("PUBLISHED");
                        ride3.setAvailableSeats(2);
                        ride3.setTotalSeats(4);
                        ride3.setDepartureTime(LocalDateTime.now().plusHours(2));
                        ride3.setFare(180.00);
                        ride3.setRequestedAt(LocalDateTime.now().minusMinutes(5));
                        rideRepository.save(ride3);
                        
                        // Driver 4 - Pooja Verma (Distinct driver for ride4 to satisfy idx_driver_active_ride)
                        User driver4 = userRepository.findByEmail("pooja@test.com");
                        if (driver4 == null) {
                            driver4 = new User();
                            driver4.setName("Pooja Verma");
                            driver4.setEmail("pooja@test.com");
                            driver4.setPassword("password123");
                            driver4.setRole("DRIVER");
                            driver4.setPhoneNumber("9995556666");
                            driver4.setAvatarUrl("https://i.pravatar.cc/150?u=pooja");
                            userRepository.save(driver4);
                        }

                        final Long driver4Id = driver4.getId();
                        if (vehicleRepository.findAll().stream().noneMatch(v -> v.getDriver() != null && v.getDriver().getId().equals(driver4Id))) {
                            Vehicle vehicle4 = new Vehicle();
                            vehicle4.setMake("Tata");
                            vehicle4.setModel("Nexon");
                            vehicle4.setYear("2023");
                            vehicle4.setColor("Blue");
                            vehicle4.setLicensePlate("DL 4C 8899");
                            vehicle4.setImageUrl("https://images.unsplash.com/photo-1549317661-bd32c8ce0db2?w=200");
                            vehicle4.setDriver(driver4);
                            vehicleRepository.save(vehicle4);
                        }

                        Ride ride4 = new Ride();
                        ride4.setRider(rider);
                        ride4.setDriver(driver4);
                        ride4.setPickupLocation("IGI Airport T3, New Delhi");
                        ride4.setDropoffLocation("Cyber Hub, Gurgaon");
                        ride4.setStatus("PUBLISHED");
                        ride4.setAvailableSeats(4);
                        ride4.setTotalSeats(4);
                        ride4.setDepartureTime(LocalDateTime.now().plusHours(3));
                        ride4.setFare(420.00);
                        ride4.setRequestedAt(LocalDateTime.now().minusMinutes(2));
                        rideRepository.save(ride4);
                        
                        // Driver 5 - Amit Sharma (Distinct driver for ride5 to satisfy idx_driver_active_ride)
                        User driver5 = userRepository.findByEmail("amit@test.com");
                        if (driver5 == null) {
                            driver5 = new User();
                            driver5.setName("Amit Sharma");
                            driver5.setEmail("amit@test.com");
                            driver5.setPassword("password123");
                            driver5.setRole("DRIVER");
                            driver5.setPhoneNumber("9997778888");
                            driver5.setAvatarUrl("https://i.pravatar.cc/150?u=amit");
                            userRepository.save(driver5);
                        }

                        final Long driver5Id = driver5.getId();
                        if (vehicleRepository.findAll().stream().noneMatch(v -> v.getDriver() != null && v.getDriver().getId().equals(driver5Id))) {
                            Vehicle vehicle5 = new Vehicle();
                            vehicle5.setMake("Honda");
                            vehicle5.setModel("City");
                            vehicle5.setYear("2022");
                            vehicle5.setColor("White");
                            vehicle5.setLicensePlate("HR 26 CN 1020");
                            vehicle5.setImageUrl("https://images.unsplash.com/photo-1550355291-bbee04a92027?w=200");
                            vehicle5.setDriver(driver5);
                            vehicleRepository.save(vehicle5);
                        }

                        Ride ride5 = new Ride();
                        ride5.setRider(rider);
                        ride5.setDriver(driver5);
                        ride5.setPickupLocation("Saket Metro Station, New Delhi");
                        ride5.setDropoffLocation("Sector 29, Gurgaon");
                        ride5.setStatus("PUBLISHED");
                        ride5.setAvailableSeats(1);
                        ride5.setTotalSeats(4);
                        ride5.setDepartureTime(LocalDateTime.now().plusMinutes(45));
                        ride5.setFare(310.00);
                        ride5.setRequestedAt(LocalDateTime.now().minusMinutes(1));
                        rideRepository.save(ride5);
                        
                        System.out.println("Additional diverse published rides and cars seeded successfully.");
                    }
                }
            } catch (Exception e) {
                System.err.println(">>> DummyDataSeeder warning: " + e.getMessage());
            }
        };
    }
}
