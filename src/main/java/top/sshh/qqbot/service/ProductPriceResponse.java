package top.sshh.qqbot.service;

import org.springframework.data.repository.CrudRepository;
import top.sshh.qqbot.data.ProductPrice;

import java.time.LocalDateTime;
import java.util.List;

public interface ProductPriceResponse extends CrudRepository<ProductPrice, Long> {

    List<ProductPrice> findByNameAndTimeIsAfterOrderByTimeAsc(String name, LocalDateTime from);

    List<ProductPrice> findTop3ByNameAndTimeIsBeforeOrderByTimeDesc(String name, LocalDateTime from);

    ProductPrice findFirstByNameAndTimeIsBeforeOrderByTimeDesc(String name, LocalDateTime from);

    ProductPrice getFirstByNameOrderByTimeDesc(String name);

    boolean existsByName(String name);

    boolean existsByCodeAndTimeIsBeforeOrderByTimeDesc(String code, LocalDateTime from);

}
