package com.bookstore.order.controller;

import com.bookstore.order.dto.OrderContactForm;
import com.bookstore.order.model.PaymentMethod;
import com.bookstore.order.model.ReceivingMethod;
import com.bookstore.order.service.ShopSettings;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;

/** Kiểm tra bổ sung dùng chung cho Checkout và Order by link */
final class FormChecks {

    private FormChecks() {}

    static void validate(OrderContactForm form, BindingResult result, ShopSettings shop) {
        if (form.isDelivery()) {
            if (isBlank(form.getAddress())) {
                result.rejectValue("address", "required", "Please enter your delivery address");
            }
            if (isBlank(form.getCity())) {
                result.rejectValue("city", "required", "Please enter your city");
            }
        }
        if (!isBlank(form.getVoucherCode()) && shop.voucherPercent(form.getVoucherCode()).isEmpty()) {
            result.rejectValue("voucherCode", "invalid", "This voucher code is not valid");
        }
    }

    static void addOptions(Model model) {
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("receivingMethods", ReceivingMethod.values());
        model.addAttribute("linkPaymentOptions", com.bookstore.order.model.LinkPaymentOption.values());
        model.addAttribute("coverTypes", com.bookstore.order.model.CoverType.values());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
