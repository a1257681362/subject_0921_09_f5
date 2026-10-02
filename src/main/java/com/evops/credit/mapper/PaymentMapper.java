package com.evops.credit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.credit.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    /** 取全局唯一回款单号序列下一个值。 */
    @Select("SELECT NEXT VALUE FOR seq_payment_no")
    long nextPaymentSeq();
}
