package io.github.kapillohani023.mypaas.dashboard;

import io.github.kapillohani023.mypaas.core.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/** Maps expected page failures to the dashboard error view instead of a stack-trace page. */
@ControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardPageExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ModelAndView domain(DomainException exception) {
        ModelAndView view = new ModelAndView("error");
        view.setStatus(HttpStatus.valueOf(exception.status()));
        view.addObject("status", exception.status());
        view.addObject("error", exception.getMessage());
        return view;
    }
}
