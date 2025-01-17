package com.machao.base.controller;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;

import com.machao.base.model.exception.ResourceNotFoundException;
import com.machao.base.model.exception.StaticResourceDeleteErrorException;
import com.machao.base.model.exception.config.StaticResourceConfig;
import com.machao.base.model.mq.audio.request.AudioConvertRequest;
import com.machao.base.model.mq.audio.request.AudioDeleteRequest;
import com.machao.base.model.mq.audio.response.AudioDeleteResponse;
import com.machao.base.model.mq.image.request.ImageDeleteRequest;
import com.machao.base.model.mq.image.response.ImageDeleteResponse;
import com.machao.base.model.mq.video.request.VideoConvertRequest;
import com.machao.base.model.mq.video.request.VideoDeleteRequest;
import com.machao.base.model.mq.video.response.VideoDeleteResponse;
import com.machao.base.model.persit.StaticResource;
import com.machao.base.model.persit.StaticResource.Type;
import com.machao.base.model.persit.User;
import com.machao.base.service.AudioMessageService;
import com.machao.base.service.ImageMessageService;
import com.machao.base.service.StaticResourceService;
import com.machao.base.service.VideoMessageService;
import com.machao.base.utils.SecurityUtils;

import io.swagger.annotations.ApiOperation;

@RestController
@RestControllerAdvice 
@RequestMapping("/static-resource")
public class StaticResourceController extends BaseController{
	private Logger logger = LoggerFactory.getLogger(StaticResourceController.class);
	
	@Autowired
	private StaticResourceConfig staticResourceConfig;
	
	@Autowired
	private StaticResourceService staticResourceService;

	@Autowired
	private ImageMessageService imageMessageService;
	@Autowired
	private AudioMessageService audioMessageService;
	@Autowired
	private VideoMessageService videoMessageService;
	
	@ApiOperation("list static resource")
	@PreAuthorize("authenticated and hasPermission('/static-resource/type/{type}', 'static-resource:list')")
	@GetMapping("/type/{type}")
	public ResponseEntity<Page<StaticResource>> list(@PathVariable Type type, Pageable pageable) {
		return ResponseEntity.ok(staticResourceService.pageByUserAndType(obtainCurrentUser(), type, pageable));
	}
	
	@ApiOperation("load static resource")
	@PreAuthorize("authenticated and hasPermission('/static-resource/{id}', 'static-resource:load')")
	@GetMapping("/{id}")
	public ResponseEntity<StaticResource> load(@PathVariable String id) {
		StaticResource staticResource = staticResourceService.findById(id).orElseThrow(ResourceNotFoundException::new);
		this.checkStaticResourceAuthorize(staticResource);
		return ResponseEntity.ok(staticResource);
	}
	
	@ApiOperation("add static resource")
	@PreAuthorize("authenticated and hasPermission('/static-resource/', 'static-resource:add')")
	@PostMapping("/")
	public ResponseEntity<StaticResource> add(@RequestParam("file") MultipartFile file, @RequestParam("type") StaticResource.Type type) {
		if (file.isEmpty()) return ResponseEntity.badRequest().build();
		if(StaticResource.Type.image.equals(type)) {
			if(!file.getContentType().startsWith("image")) {
				return ResponseEntity.badRequest().build();
			}
		} else if(StaticResource.Type.audio.equals(type)) {
			if(!file.getContentType().startsWith("audio")) {
				return ResponseEntity.badRequest().build();
			}
		} else if(StaticResource.Type.video.equals(type)) {
			if(!file.getContentType().startsWith("video")) {
				return ResponseEntity.badRequest().build();
			}
		}
		
		User user = obtainCurrentUser();
		
		String fileName = file.getOriginalFilename();
		String uuid = UUID.randomUUID().toString();
		String relativePath = user.getId() + File.separator + type.toString() + File.separator + uuid + File.separator + uuid + fileName.substring(fileName.lastIndexOf("."));

		StaticResource staticResource = new StaticResource();
		staticResource.setRelativePath(relativePath);
		staticResource.setType(type);
		staticResource.setContentType(file.getContentType());
		staticResource.setUser(obtainCurrentUser());
		staticResource.setOriginName(file.getOriginalFilename());
		staticResource.setHandled(StaticResource.Type.image.equals(type));
		StaticResource savedStaticResource = staticResourceService.insert(staticResource);
		
		try {
			logger.info("file {} uploading by {}", file.getOriginalFilename(), SecurityUtils.getPrincipal());
			File targetFile = new File(staticResourceConfig.getRootPath(), relativePath);
			FileUtils.copyInputStreamToFile(file.getInputStream(), targetFile);
			
			// send file to mq to convert to stream
			if(Type.audio.equals(type)) {
				this.audioMessageService.convert(new AudioConvertRequest(savedStaticResource));
			} else if(Type.video.equals(type)) {
				this.videoMessageService.convert(new VideoConvertRequest(savedStaticResource));
			}
			
			logger.info("file {} uploaded to {}", file.getOriginalFilename(), targetFile);
		} catch (IOException e) {
			this.staticResourceService.deleteById(savedStaticResource.getId());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
		
		return ResponseEntity.ok(savedStaticResource);
	}
	
	@ApiOperation("delete static resource")
	@PreAuthorize("authenticated and hasPermission('/static-resource/{id}', 'static-resource:delete')")
	@DeleteMapping("/{id}")
	public ResponseEntity<StaticResource> delete(@PathVariable String id) {
		StaticResource staticResource = this.staticResourceService.findById(id).orElseThrow(ResourceNotFoundException::new);
		this.checkStaticResourceAuthorize(staticResource);
		
		logger.info("file {} deleting", staticResource.getPath());
		File file = new File(staticResource.getPath());
		if(file.exists()) {
			if(Type.image.equals(staticResource.getType())) {
				ImageDeleteResponse imageDeleteResponse = imageMessageService.delete(new ImageDeleteRequest(staticResource));
				if(!imageDeleteResponse.isSuccess()) throw new StaticResourceDeleteErrorException();
			} else if(Type.audio.equals(staticResource.getType())) { 
				AudioDeleteResponse audioDeleteResponse = audioMessageService.delete(new AudioDeleteRequest(staticResource));
				if(!audioDeleteResponse.isSuccess()) throw new StaticResourceDeleteErrorException();
			} else if(Type.video.equals(staticResource.getType())) { 
				VideoDeleteResponse videoDeleteResponse = videoMessageService.delete(new VideoDeleteRequest(staticResource));
				if(!videoDeleteResponse.isSuccess()) throw new StaticResourceDeleteErrorException();
			}
		}
		logger.info("file {} deleted", staticResource.getPath());
		
		this.staticResourceService.deleteById(id);
		
		return ResponseEntity.ok(staticResource);
	}
	
	@ApiOperation("delete static resource")
	@PreAuthorize("authenticated and hasPermission('/static-resource/{id}/public/toggle', 'static-resource:public-toggle')")
	@PutMapping("/{id}/public/toggle")
	public ResponseEntity<StaticResource> togglePublicAndPrivate(@PathVariable String id) {
		StaticResource staticResource = this.staticResourceService.findById(id).orElseThrow(ResourceNotFoundException::new);
		this.checkStaticResourceAuthorize(staticResource);
		
		staticResource.setPublic(!staticResource.isPublic());
		this.staticResourceService.update(staticResource);
		
		logger.info("toggle public for type: {}, path: {}, public: {}", staticResource.getType(), staticResource.getPath(), staticResource.isPublic());
		
		return ResponseEntity.ok(staticResource);
	}
	
}
