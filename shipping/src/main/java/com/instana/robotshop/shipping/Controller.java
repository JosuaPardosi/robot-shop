package com.instana.robotshop.shipping;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

// 🔹 Tambahkan import Instana SDK
import com.instana.sdk.annotation.Span;
import com.instana.sdk.support.SpanSupport;

@RestController
public class Controller {
    private static final Logger logger = LoggerFactory.getLogger(Controller.class);

    private String CART_URL = String.format("http://%s/shipping/", getenv("CART_ENDPOINT", "cart"));

    public static List<byte[]> bytesGlobal = Collections.synchronizedList(new ArrayList<byte[]>());

    @Autowired
    private CityRepository cityrepo;

    @Autowired
    private CodeRepository coderepo;

    private String getenv(String key, String def) {
        String val = System.getenv(key);
        val = val == null ? def : val;
        return val;
    }

    @GetMapping(path = "/memory")
    @Span(value = "allocate_memory", type = Span.Type.ENTRY)
    public int memory() {
        SpanSupport.annotate("operation", "allocate_memory");

        byte[] bytes = new byte[1024 * 1024 * 25];
        Arrays.fill(bytes, (byte) 8);
        bytesGlobal.add(bytes);

        logger.info("Allocated memory blocks: {}", bytesGlobal.size());
        return bytesGlobal.size();
    }

    @GetMapping(path = "/free")
    @Span(value = "free_memory", type = Span.Type.ENTRY)
    public int free() {
        SpanSupport.annotate("operation", "free_memory");
        bytesGlobal.clear();
        logger.info("Freed memory blocks");
        return bytesGlobal.size();
    }

    @GetMapping("/health")
    @Span(value = "health_check", type = Span.Type.ENTRY)
    public String health() {
        return "OK";
    }

    @GetMapping("/count")
    @Span(value = "count_cities", type = Span.Type.ENTRY)
    public String count() {
        long count = cityrepo.count();
        SpanSupport.annotate("city.count", String.valueOf(count));
        return String.valueOf(count);
    }

    @GetMapping("/codes")
    @Span(value = "get_codes", type = Span.Type.ENTRY)
    public Iterable<Code> codes() {
        logger.info("all codes");
        Iterable<Code> codes = coderepo.findAll(Sort.by(Sort.Direction.ASC, "name"));
        SpanSupport.annotate("code.count", String.valueOf(((List<?>) codes).size()));
        return codes;
    }

    @GetMapping("/cities/{code}")
    @Span(value = "get_cities_by_code", type = Span.Type.ENTRY)
    public List<City> cities(@PathVariable String code) {
        logger.info("cities by code {}", code);
        SpanSupport.annotate("city.code", code);
        List<City> cities = cityrepo.findByCode(code);
        SpanSupport.annotate("city.count", String.valueOf(cities.size()));
        return cities;
    }

    @GetMapping("/match/{code}/{text}")
    @Span(value = "match_city_text", type = Span.Type.ENTRY)
    public List<City> match(@PathVariable String code, @PathVariable String text) {
        logger.info("match code {} text {}", code, text);
        SpanSupport.annotate("city.code", code);
        SpanSupport.annotate("search.text", text);

        if (text.length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        List<City> cities = cityrepo.match(code, text);
        if (cities.size() > 10) {
            cities = cities.subList(0, 9);
        }

        SpanSupport.annotate("result.count", String.valueOf(cities.size()));
        return cities;
    }
    @GetMapping("/calc/{id}")
    @Span(value = "calculate_shipping_cost", type = Span.Type.ENTRY)
    public Ship caclc(@PathVariable long id) {
        double homeLatitude = 51.164896;
        double homeLongitude = 7.068792;
        // menambahkan tags kustom
        SpanSupport.annotate("calc.id", String.valueOf(id));
        City city = cityrepo.findById(id);
        if (city == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "city not found");
        }
        // Kalkulasi
        Calculator calc = new Calculator(city);
        long distance = calc.getDistance(homeLatitude, homeLongitude);
        double cost = Math.rint(distance * 5) / 100.0;
        Ship ship = new Ship(distance, cost);

        // Tambahkan tag baru
        SpanSupport.annotate("calc.distance", String.valueOf(distance));
        SpanSupport.annotate("calc.cost", String.valueOf(cost));
        SpanSupport.annotate("calc.customTag", "nilai_kustom");
        logger.info("shipping {}", ship);
        return ship;
    }


//    @GetMapping("/calc/{id}")
//    @Span(value = "calculate_shipping_cost", type = Span.Type.ENTRY)
//    public Ship caclc(@PathVariable long id) {
//        double homeLatitude = 51.164896;
//        double homeLongitude = 7.068792;
//
//        logger.info("Calculation for {}", id);
//        SpanSupport.annotate("calc.id", String.valueOf(id));
//
//        City city = cityrepo.findById(id);
//        if (city == null) {
//            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "city not found");
//        }
//
//        Calculator calc = new Calculator(city);
//        long distance = calc.getDistance(homeLatitude, homeLongitude);
//        double cost = Math.rint(distance * 5) / 100.0;
//        Ship ship = new Ship(distance, cost);
//
//        SpanSupport.annotate("calc.distance", String.valueOf(distance));
//        SpanSupport.annotate("calc.cost", String.valueOf(cost));
//
//        logger.info("shipping {}", ship);
//        return ship;
//    }

    @PostMapping(path = "/confirm/{id}", consumes = "application/json", produces = "application/json")
    @Span(value = "confirm_shipping", type = Span.Type.ENTRY)
    public String confirm(@PathVariable String id, @RequestBody String body) {
        logger.info("confirm id: {}", id);
        logger.info("body {}", body);
        SpanSupport.annotate("confirm.id", id);

        CartHelper helper = new CartHelper(CART_URL);
        String cart = helper.addToCart(id, body);

        if (cart.equals("")) {
            SpanSupport.annotate("confirm.status", "failed");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cart not found");
        }

        SpanSupport.annotate("confirm.status", "successssssssssssssss");
        return cart;
    }
}
