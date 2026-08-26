package com.rishab.scheduler.web;

import com.rishab.scheduler.jobs.DependencyCycleException;
import com.rishab.scheduler.jobs.IllegalJobStateTransitionException;
import com.rishab.scheduler.jobs.JobNotFoundException;
import com.rishab.scheduler.jobs.UnknownDependencyException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(JobNotFoundException.class)
	public ResponseEntity<ApiError> handleNotFound(JobNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError(Instant.now(), 404, "Not Found", ex.getMessage(), List.of()));
	}

	@ExceptionHandler(IllegalJobStateTransitionException.class)
	public ResponseEntity<ApiError> handleIllegalTransition(IllegalJobStateTransitionException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError(Instant.now(), 409, "Conflict", ex.getMessage(), List.of()));
	}

	@ExceptionHandler(UnknownDependencyException.class)
	public ResponseEntity<ApiError> handleUnknownDependency(UnknownDependencyException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiError(Instant.now(), 400, "Bad Request", ex.getMessage(), List.of()));
	}

	@ExceptionHandler(DependencyCycleException.class)
	public ResponseEntity<ApiError> handleDependencyCycle(DependencyCycleException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiError(Instant.now(), 400, "Bad Request", ex.getMessage(), List.of()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		List<String> details = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.toList();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiError(Instant.now(), 400, "Bad Request", "validation failed", details));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		String message = "invalid value for parameter '%s': %s".formatted(ex.getName(), ex.getValue());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiError(Instant.now(), 400, "Bad Request", message, List.of()));
	}
}
