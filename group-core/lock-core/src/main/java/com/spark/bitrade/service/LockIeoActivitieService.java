package com.spark.bitrade.service;

import com.alibaba.fastjson.JSONArray;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.spark.bitrade.annotation.ReadDataSource;
import com.spark.bitrade.constant.*;
import com.spark.bitrade.dao.LockCoinDetailDao;
import com.spark.bitrade.dao.LockIeoRestitutionIncomePlanDao;
import com.spark.bitrade.entity.*;
import com.spark.bitrade.entity.transform.AuthMember;
import com.spark.bitrade.service.Base.BaseService;
import com.spark.bitrade.util.MessageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
public class LockIeoActivitieService extends BaseService {

    @Autowired
    private LockCoinDetailDao lockCoinDetailDao;
    @Autowired
    private LockIeoRestitutionIncomePlanDao lockIeoRestitutionIncomePlanDao;
    @Autowired
    private LockCoinActivitieSettingService lockCoinActivitieSettingService;
    @Autowired
    private MemberTransactionService memberTransactionService;
    @Autowired
    private MemberWalletService memberWalletService;
    @Autowired
    private LocaleMessageSourceService msService;
    @Autowired
    private ISilkDataDistService silkDataDistService;

    /**
     * ieoBCC 锁仓
     * @param setting
     * @param user
     * @param coinCnyPrice
     * @param coinUSDTPrice
     * @param usdtPrice
     * @param amount
     */
    @Transactional(rollbackFor = Exception.class)
    public void ieoBccLock(LockCoinActivitieSetting setting, AuthMember user, BigDecimal coinCnyPrice,
                         BigDecimal coinUSDTPrice, BigDecimal usdtPrice, BigDecimal amount) {

        SilkDataDist dataDist = silkDataDistService.findByIdAndKey("IEO_ACTIVITY_BCC", "LOCK_PERCENT");
        Assert.notNull(dataDist, "活动配置未找到");

        BigDecimal lockPercent = new BigDecimal(dataDist.getDictVal());
        // 锁定活动币
        MemberWallet activityMemberWallet = memberWalletService.findCacheByCoinUnitAndMemberId(setting.getCoinSymbol(), user.getId());
        if(lockPercent.compareTo(BigDecimal.ONE) < 0) {
            // 锁仓总数
            amount = amount.multiply(lockPercent);
        }
        log.info("【IEO锁仓】>增加用户 {} 的 {} 锁仓币数", user.getId(), setting.getCoinSymbol());
        //增加用户IEO锁仓BTTC数freezeBalanceToLockBalance
        MessageResult activityWalletResult = memberWalletService.freezeBalanceToLockBalance(activityMemberWallet, amount);
        if (activityWalletResult.getCode() != 0) {
            throw new IllegalArgumentException(msService.getMessage("INSUFFICIENT_BALANCE"));
        }

        // 开始解锁时间
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, setting.getBeginDays());
        // 记录锁仓详情
        LockCoinDetail lockCoinDetail = new LockCoinDetail();
        lockCoinDetail.setMemberId(user.getId());
        lockCoinDetail.setType(LockType.IEO);
        lockCoinDetail.setCoinUnit(setting.getCoinSymbol());
        lockCoinDetail.setRefActivitieId(setting.getId());
        lockCoinDetail.setTotalAmount(amount);
        lockCoinDetail.setLockPrice(coinCnyPrice);
        lockCoinDetail.setRemainAmount(amount);
        lockCoinDetail.setLockTime(new Date());
        lockCoinDetail.setPlanUnlockTime(calendar.getTime());
        lockCoinDetail.setPlanIncome(BigDecimal.ZERO);
        lockCoinDetail.setStatus(LockStatus.LOCKED);
        lockCoinDetail.setUnlockTime(calendar.getTime());
        lockCoinDetail.setUsdtPriceCNY(usdtPrice);
        lockCoinDetail.setTotalCNY(coinCnyPrice.multiply(amount));
        lockCoinDetail.setRemark(String.format("IEO锁仓：%f", amount));
        lockCoinDetail.setLockDays(setting.getLockDays());
        lockCoinDetail.setEarningRate(BigDecimal.ZERO);
        lockCoinDetail.setUnitPerAmount(BigDecimal.ONE);
        lockCoinDetail.setLockRewardSatus(LockRewardSatus.DEFAULT_REWARD);
        lockCoinDetail.setSmsSendStatus(SmsSendStatus.NO_SMS_SEND);
        lockCoinDetail.setLockCycle(setting.getLockCycle());
        lockCoinDetail.setBeginDays(setting.getBeginDays());
        lockCoinDetail.setCycleDays(setting.getCycleDays());
        lockCoinDetail.setCycleRatio(setting.getCycleRatio());
        LockCoinDetail detail = lockCoinDetailDao.save(lockCoinDetail);

        // 记录返还计划
        List<LockIeoRestitutionIncomePlan> plans = new ArrayList<>();
        JSONArray array = JSONArray.parseArray(setting.getCycleRatio());
        BigDecimal restitutionRatio = array.getBigDecimal(0);
        Calendar calendarTime = Calendar.getInstance();
        for (int i = 0; i < setting.getLockCycle(); i++) {
            if (i == 0) {
                calendarTime.add(Calendar.DAY_OF_MONTH, setting.getBeginDays());
            } else {
                calendarTime.add(Calendar.DAY_OF_MONTH, setting.getCycleDays());
            }
            LockIeoRestitutionIncomePlan plan = new LockIeoRestitutionIncomePlan();
            plan.setMemberId(user.getId());
            plan.setRestitutionAmount(amount.multiply(restitutionRatio));
            plan.setComment(String.format("第%d期", i + 1));
            plan.setLockDetailId(detail.getId());
            plan.setPeriod(i + 1);
            plan.setStatus(LockBackStatus.BACK);
            plan.setSymbol(setting.getCoinSymbol());
            plan.setRewardTime(calendarTime.getTime());
            plan.setCreateTime(new Date());
            plans.add(plan);
        }
        lockIeoRestitutionIncomePlanDao.save(plans);

        // 更新活动参与数量
        lockCoinActivitieSettingService.updateBoughtAmount(amount, setting.getId());

        // 保存BTTC扣除资金记录
        MemberTransaction usdtMemberTransaction = new MemberTransaction();
        usdtMemberTransaction.setMemberId(user.getId());
        usdtMemberTransaction.setAmount(BigDecimal.ZERO.subtract(amount));
        usdtMemberTransaction.setType(TransactionType.IEO_ACTIVITY);
        usdtMemberTransaction.setSymbol(setting.getCoinSymbol());
        usdtMemberTransaction.setRefId(String.valueOf(lockCoinDetail.getId()));
        memberTransactionService.save(usdtMemberTransaction);
    }

    /**
     * 闪对
     * baseSymbol -> coinSymbol
     */
    public MessageResult fastExchange(RestTemplate restTemplate, AuthMember user, String baseSymbol, String coinSymbol, BigDecimal btAmount) {
        return exchange(restTemplate, user, baseSymbol, coinSymbol, btAmount, 0);
    }

    /**
     * 闪对返回
     * coinSymbol -> baseSymbol
     */
    public MessageResult fastExchangeBack(RestTemplate restTemplate, AuthMember user, String baseSymbol, String coinSymbol, BigDecimal bttcAmount) {
        return exchange(restTemplate, user, baseSymbol, coinSymbol, bttcAmount, 1);
    }

    /**
     * baseSymbol和coinSymbol 兑换
     *
     * @param user      用户
     * @param amount    数量
     * @param direction 方向 0=买入(闪兑基币->闪兑币)/1=卖出(闪兑币->闪兑基币)
     */
    private MessageResult exchange(RestTemplate restTemplate, AuthMember user, String baseSymbol, String coinSymbol, BigDecimal amount, int direction) {
        String url = String.format("http://%s/exchange/fast/api/addOrder", "service-exchange-api");
        MultiValueMap<String, String> requestEntity = new LinkedMultiValueMap<>();
        requestEntity.add("memberId", String.valueOf(user.getId()));
        requestEntity.add("appId", user.getPlatform());
        requestEntity.add("baseSymbol", baseSymbol);
        requestEntity.add("coinSymbol", coinSymbol);
        requestEntity.add("amount", String.valueOf(amount.doubleValue()));
        requestEntity.add("direction", String.valueOf(direction));
        log.info("闪兑请求参数：{}", requestEntity.toSingleValueMap().toString());
        ResponseEntity<MessageResult> pricResult = restTemplate.postForEntity(url, requestEntity, MessageResult.class);
        MessageResult mr = pricResult.getBody();
        log.info("闪兑[{}->{}]，方向：{}, 返回结果：{}", baseSymbol, coinSymbol, direction, mr);
        if (mr.isSuccess()) {
            Map map = (Map) mr.getData();
            Object rtAmount = map.get("tradedAmount");
            return MessageResult.success("", new BigDecimal(String.valueOf(rtAmount)));
        } else {
            return MessageResult.error(mr.getMessage());
        }
    }
}
