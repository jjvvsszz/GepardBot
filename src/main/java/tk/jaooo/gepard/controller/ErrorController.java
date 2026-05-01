package tk.jaooo.gepard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ErrorController {

    @GetMapping("/error")
    public String errorPage(@RequestParam(value = "msg", required = false) String msg, Model model) {
        model.addAttribute("errorMessage", msg != null ? msg : "Ocorreu um erro inesperado.");
        return "error";
    }
}
