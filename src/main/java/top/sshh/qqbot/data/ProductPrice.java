//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.data;

import com.alibaba.fastjson2.annotation.JSONField;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
public class ProductPrice {
    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY
    )
    private Long id;
    private int price;
    private String name;
    private String code;
    private LocalDateTime time;
    @Transient
    @JSONField(serialize = false)
    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    @Transient
    private LocalDateTime from;
    @Transient
    private LocalDateTime to;
    @Transient
    @JSONField(serialize = false)
    private int herbCount = 0;

    @Transient
    public String getTime2() {
        return this.time.format(this.formatter);
    }

    public ProductPrice() {
    }

    public int getHerbCount() {
        return herbCount;
    }

    public void setHerbCount(int herbCount) {
        this.herbCount = herbCount;
    }

    public Long getId() {
        return this.id;
    }

    public int getPrice() {
        return this.price;
    }

    public String getName() {
        return this.name;
    }

    public String getCode() {
        return this.code;
    }

    public LocalDateTime getTime() {
        return this.time;
    }

    public DateTimeFormatter getFormatter() {
        return this.formatter;
    }

    public LocalDateTime getFrom() {
        return this.from;
    }

    public LocalDateTime getTo() {
        return this.to;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    public void setPrice(final int price) {
        this.price = price;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public void setCode(final String code) {
        this.code = code;
    }

    public void setTime(final LocalDateTime time) {
        this.time = time;
    }

    public void setFormatter(final DateTimeFormatter formatter) {
        this.formatter = formatter;
    }

    public void setFrom(final LocalDateTime from) {
        this.from = from;
    }

    public void setTo(final LocalDateTime to) {
        this.to = to;
    }

    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof ProductPrice)) {
            return false;
        } else {
            ProductPrice other = (ProductPrice)o;
            if (!other.canEqual(this)) {
                return false;
            } else if (this.getPrice() != other.getPrice()) {
                return false;
            } else {
                label97: {
                    Object this$id = this.getId();
                    Object other$id = other.getId();
                    if (this$id == null) {
                        if (other$id == null) {
                            break label97;
                        }
                    } else if (this$id.equals(other$id)) {
                        break label97;
                    }

                    return false;
                }

                Object this$name = this.getName();
                Object other$name = other.getName();
                if (this$name == null) {
                    if (other$name != null) {
                        return false;
                    }
                } else if (!this$name.equals(other$name)) {
                    return false;
                }

                Object this$code = this.getCode();
                Object other$code = other.getCode();
                if (this$code == null) {
                    if (other$code != null) {
                        return false;
                    }
                } else if (!this$code.equals(other$code)) {
                    return false;
                }

                label76: {
                    Object this$time = this.getTime();
                    Object other$time = other.getTime();
                    if (this$time == null) {
                        if (other$time == null) {
                            break label76;
                        }
                    } else if (this$time.equals(other$time)) {
                        break label76;
                    }

                    return false;
                }

                Object this$formatter = this.getFormatter();
                Object other$formatter = other.getFormatter();
                if (this$formatter == null) {
                    if (other$formatter != null) {
                        return false;
                    }
                } else if (!this$formatter.equals(other$formatter)) {
                    return false;
                }

                Object this$from = this.getFrom();
                Object other$from = other.getFrom();
                if (this$from == null) {
                    if (other$from != null) {
                        return false;
                    }
                } else if (!this$from.equals(other$from)) {
                    return false;
                }

                Object this$to = this.getTo();
                Object other$to = other.getTo();
                if (this$to == null) {
                    if (other$to != null) {
                        return false;
                    }
                } else if (!this$to.equals(other$to)) {
                    return false;
                }

                return true;
            }
        }
    }

    protected boolean canEqual(final Object other) {
        return other instanceof ProductPrice;
    }

    public int hashCode() {
        int result = 1;
        result = result * 59 + this.getPrice();
        Object $id = this.getId();
        result = result * 59 + ($id == null ? 43 : $id.hashCode());
        Object $name = this.getName();
        result = result * 59 + ($name == null ? 43 : $name.hashCode());
        Object $code = this.getCode();
        result = result * 59 + ($code == null ? 43 : $code.hashCode());
        Object $time = this.getTime();
        result = result * 59 + ($time == null ? 43 : $time.hashCode());
        Object $formatter = this.getFormatter();
        result = result * 59 + ($formatter == null ? 43 : $formatter.hashCode());
        Object $from = this.getFrom();
        result = result * 59 + ($from == null ? 43 : $from.hashCode());
        Object $to = this.getTo();
        result = result * 59 + ($to == null ? 43 : $to.hashCode());
        return result;
    }

    public String toString() {
        return "ProductPrice(id=" + this.getId() + ", price=" + this.getPrice() + ", name=" + this.getName() + ", code=" + this.getCode() + ", time=" + this.getTime() + ", formatter=" + this.getFormatter() + ", from=" + this.getFrom() + ", to=" + this.getTo() + ")";
    }
}
