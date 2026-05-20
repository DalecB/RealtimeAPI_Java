package com.jake.realtimeapi.projects.application;

import com.jake.realtimeapi.projects.application.command.CreateProjectCommand;
import com.jake.realtimeapi.projects.domain.exception.ProjectAlreadyExistsException;
import com.jake.realtimeapi.projects.domain.exception.ProjectNotFoundException;
import com.jake.realtimeapi.projects.domain.model.Project;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// JUnit 5 테스트에서 Mockito 어노테이션(@Mock, @InjectMocks)을 활성화한다.
@ExtendWith(MockitoExtension.class)
public class ProjectApplicationServiceTest {

    // 서비스가 의존하는 저장소를 실제 구현 대신 mock으로 대체한다.
    // 이 테스트는 DB 저장 자체가 아니라 "서비스가 저장소를 어떻게 사용하는지"를 본다.
    @Mock
    private ProjectRepository projectRepository;

    // 테스트 대상인 실제 서비스 객체다.
    // 위에서 만든 mock(projectRepository)을 주입해서 서비스만 단독으로 검증한다.
    @InjectMocks
    private ProjectApplicationService service;

    @Test
    void create_whenNameDoesNotExist_savesNewProjectAndReturnsIt() {
        // save()가 성공적으로 끝난 뒤 저장소가 돌려줄 최종 결과값을 미리 만든다.
        UUID projectId = UUID.randomUUID();
        String name = "Project-1";
        Long adminId = 1L;
        // 테스트는 항상 고정된 시간을 쓰는 편이 안전하다.
        // Instant.now()를 쓰면 테스트가 불필요하게 비결정적이 된다.
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        // 서비스에 전달할 입력값이다.
        // 컨트롤러 테스트의 request body 역할을 서비스 테스트에서는 command가 대신한다.
        CreateProjectCommand command = new CreateProjectCommand(name, adminId);
        // repository.save(...)가 최종적으로 반환한다고 가정하는 객체다.
        // 서비스의 반환값 검증은 이 객체를 기준으로 본다.
        Project expected = new Project(projectId, adminId, name, createdAt);

        // "같은 이름의 프로젝트가 없다"는 정상 케이스를 명시적으로 만든다.
        // 이 줄이 없으면 Mockito 기본값(false)에 암묵적으로 의존하게 된다.
        when(projectRepository.existsByName(name)).thenReturn(false);
        // 서비스가 어떤 Project를 save(...)로 넘기든, 저장이 끝나면 expected를 돌려주도록 설정한다.
        // 즉 DB 저장 결과를 우리가 통제하는 것이다.
        when(projectRepository.save(any(Project.class))).thenReturn(expected);

        // 실제 테스트 대상 메서드를 실행한다.
        // 준비(Arrange)가 끝난 뒤 실행(Act)은 보통 한 줄로 두는 편이 읽기 쉽다.
        Project result = service.create(command);

        // save(...)에 실제로 어떤 Project가 전달되었는지 꺼내보기 위한 도구다.
        // 반환값(result)만 보면 "무엇을 저장하려 했는지"는 알 수 없어서 captor를 쓴다.
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        // 서비스가 save(...)를 호출했는지 확인하면서, 그때 넘긴 인자를 captor에 담는다.
        verify(projectRepository).save(captor.capture());
        // 방금 캡처한 "저장 직전의 Project"를 꺼낸다.
        Project captured = captor.getValue();

        // 서비스가 command의 name을 새 Project에 제대로 옮겼는지 확인한다.
        assertEquals(name, captured.name());
        // Project.newProject(...) 규칙상 저장 전에는 id/createdAt이 비어 있어야 한다.
        // adminId는 현재 로그인한 관리자에서 바로 들어와야 한다.
        assertNull(captured.id());
        assertEquals(adminId, captured.adminId());
        assertNull(captured.createdAt());

        // 중복 이름 체크를 먼저 수행했는지도 확인한다.
        // 이 검증이 있으면 create()의 핵심 분기 중 하나를 확실히 잠글 수 있다.
        verify(projectRepository).existsByName(name);

        // 최종 반환값은 repository.save(...)가 돌려준 값을 그대로 받는지 확인한다.
        // captured는 "저장 전 입력", result는 "저장 후 결과"라는 점이 다르다.
        assertEquals(projectId, result.id());
        assertEquals(name, result.name());
        assertEquals(adminId, result.adminId());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void create_whenNameAlreadyExists_throwsProjectAlreadyExistsException() {
        String name = "Project-1";
        CreateProjectCommand command = new CreateProjectCommand(name, 1L);

        // "이미 같은 이름이 존재한다"는 예외 케이스를 만든다.
        when(projectRepository.existsByName(name)).thenReturn(true);

        // 이 테스트의 기대 결과는 반환값이 아니라 예외 발생이다.
        ProjectAlreadyExistsException exception = assertThrows(
                ProjectAlreadyExistsException.class,
                () -> service.create(command)
        );

        // 어떤 이름 때문에 실패했는지 예외 메시지까지 확인해 둔다.
        assertEquals("Project already exists: name=" + name, exception.getMessage());

        // 중복 확인은 했어야 하고,
        verify(projectRepository).existsByName(name);
        // 중복이면 저장은 시도하지 않아야 한다.
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void getById_whenIdExist_returnsProject() {
        UUID projectId = UUID.randomUUID();
        String name = "Project-1";
        Long adminId = 1L;
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        Project expected = new Project(projectId, 1L, "Project-1", Instant.parse("2026-03-12T00:00:00Z"));

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(expected));

        Project result = service.getById(projectId);

        assertEquals(projectId, result.id());
        assertEquals(name, result.name());
        assertEquals(adminId, result.adminId());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void getById_whenIdDoesNotExist_throwsProjectNotFoundException() {
        UUID projectId = UUID.randomUUID();

        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        ProjectNotFoundException exception = assertThrows(
                ProjectNotFoundException.class,
                () -> service.getById(projectId)
        );

        assertEquals("Project not found: id=" + projectId, exception.getMessage());

        verify(projectRepository).findById(projectId);
    }

    @Test
    void getByName_whenNameExist_returnsProject() {
        UUID projectId = UUID.randomUUID();
        String name = "Project-1";
        Long adminId = 1L;
        Instant createdAt = Instant.parse("2026-03-12T00:00:00Z");

        Project expected = new Project(projectId, 1L, "Project-1", createdAt);

        when(projectRepository.findByName(name)).thenReturn(Optional.of(expected));

        Project result = service.getByName(name);

        assertEquals(projectId, result.id());
        assertEquals(name, result.name());
        assertEquals(adminId, result.adminId());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void getByName_whenNameDoesNotExist_throwsProjectNotFoundException() {
        String name = "Project-1";

        when(projectRepository.findByName(name)).thenReturn(Optional.empty());

        ProjectNotFoundException exception = assertThrows(
                ProjectNotFoundException.class,
                () -> service.getByName(name));

        assertEquals("Project not found: name=" + name, exception.getMessage());

        verify(projectRepository).findByName(name);
    }
}
