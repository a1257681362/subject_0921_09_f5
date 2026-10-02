package com.evops.credit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.credit.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {

    /** 取全局唯一客户编号序列下一个值，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_customer_no")
    long nextCustomerSeq();

    /**
     * 占额度（守卫更新）：只有状态正常且占用后不超额度才生效，并发超占靠这一行拦住。
     * 返回 0 表示余额不足或客户已冻结。
     */
    @Update("UPDATE t_customer SET occupied_amount = occupied_amount + #{amount}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND credit_status = 'NORMAL' AND occupied_amount + #{amount} <= credit_limit")
    int tryOccupy(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 释放额度（守卫更新）：核销/对冲释放占用，不允许把占用减成负数。
     * 返回 0 表示并发冲突或数据不一致。
     */
    @Update("UPDATE t_customer SET occupied_amount = occupied_amount - #{amount}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND occupied_amount >= #{amount}")
    int tryRelease(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
