package org.rx.crawler.controller;

import lombok.RequiredArgsConstructor;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.price.ProductCacheService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductCacheService products;
    @GetMapping("/{platform}/{productId}")
    public Result<Object> product(@PathVariable String platform, @PathVariable String productId) {
        return Result.success(products.product(platform,productId));
    }
    @ExceptionHandler(org.rx.crawler.task.common.TaskApiException.class)
    public org.springframework.http.ResponseEntity<Result<Object>> error(org.rx.crawler.task.common.TaskApiException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatus()).body(Result.fail(e.getCode(),e.getMessage(),null));
    }
}
