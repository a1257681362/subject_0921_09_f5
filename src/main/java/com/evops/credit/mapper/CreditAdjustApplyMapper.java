package com.evops.credit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.credit.entity.CreditAdjustApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CreditAdjustApplyMapper extends BaseMapper<CreditAdjustApply> {

    /** 取全局唯一调整申请号序列下一个值。 */
    @Select("SELECT NEXT VALUE FOR seq_credit_apply_no")
    long nextApplySeq();
}
