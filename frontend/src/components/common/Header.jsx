import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../../api/axios';
import './Layout.css';

const Header = () => {
  const navigate = useNavigate();
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [notifications, setNotifications] = useState([]); 
  const [isNotiOpen, setIsNotiOpen] = useState(false);    
  const notiRef = useRef(null); 

  // 💡 SSE 연결을 관리할 ref (중복 연결 방지 및 클린업 용도)
  const eventSourceRef = useRef(null);
  // 재연결 타이머/시도 횟수 (지수 백오프용)
  const reconnectTimeoutRef = useRef(null);
  const reconnectAttemptsRef = useRef(0);

  const MAX_RECONNECT_DELAY_MS = 30000;

  const clearScheduledReconnect = () => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
  };

  // 재연결 예약: 로그아웃(토큰 없음) 상태면 중단, 아니면 지수 백오프(1s→2s→4s→...→최대 30s)로 재시도
  const scheduleReconnect = () => {
    const latestToken = localStorage.getItem('accessToken');
    if (!latestToken) {
      console.log("🔒 로그인 상태가 아니므로 SSE 재연결을 시도하지 않습니다.");
      return;
    }

    const attempt = reconnectAttemptsRef.current + 1;
    reconnectAttemptsRef.current = attempt;
    const delay = Math.min(1000 * 2 ** (attempt - 1), MAX_RECONNECT_DELAY_MS);

    console.log(`⏳ SSE 재연결 예약: ${delay}ms 후 시도 (attempt ${attempt})`);
    reconnectTimeoutRef.current = setTimeout(() => {
      // 대기하는 동안 로그아웃했을 수 있으므로 재확인
      const tokenNow = localStorage.getItem('accessToken');
      if (tokenNow) {
        connectSSE(tokenNow);
      }
    }, delay);
  };

  // 1. 초기 로드 및 로그인 상태 확인
  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    const loggedIn = !!token;
    setIsLoggedIn(loggedIn);

    if (loggedIn) {
      fetchNotifications();
      // 🚀 로그인 상태라면 SSE 연결 시작
      connectSSE(token);
    }

    // 언마운트 시 SSE 연결 및 예약된 재연결 타이머 정리
    return () => {
      clearScheduledReconnect();
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, []);

  // 📡 SSE 연결 함수
const connectSSE = (token) => {
    if (eventSourceRef.current) return;
    clearScheduledReconnect();

    // 💡 주소 뒤에 반드시 토큰이 잘 붙는지 확인!
    const eventSource = new EventSource(`/api/subscribe?token=${token}`);
    eventSourceRef.current = eventSource;

    // 연결 확인 로그
    eventSource.onopen = () => {
      console.log("✅ SSE 연결이 활성화되었습니다.");
      reconnectAttemptsRef.current = 0; // 재연결 성공 시 백오프 카운터 초기화
    };

    // 💡 백엔드의 "newNotification"을 정확히 구독
    eventSource.addEventListener("newNotification", (event) => {
      console.log("🔔 SSE 수신 성공! 데이터:", event.data);

      const newNotiObj = {
        id: Date.now(),
        content: event.data, // 백엔드에서 보낸 문자열 데이터
        url: "/mypage",
        isRead: false,
        createdAt: new Date().toISOString()
      };

      setNotifications(prev => [newNotiObj, ...prev]);
    });

    eventSource.onerror = (error) => {
      console.error("❌ SSE 에러 발생:", error);
      // 브라우저 네이티브 재연결은 끊긴 시점의(만료됐을 수 있는) 토큰을 그대로 재사용하므로
      // 여기서 명시적으로 닫고, 최신 토큰으로 우리가 직접 재연결을 예약한다.
      eventSource.close();
      eventSourceRef.current = null;
      scheduleReconnect();
    };
  };

  // 2. 알림 데이터 가져오기 (조회)
  const fetchNotifications = async () => {
    try {
      const response = await api.get('/notifications');
      setNotifications(response.data);
    } catch (error) {
      console.error("알림 로드 실패:", error);
    }
  };

  // 3. 외부 클릭 시 알림창 닫기 (기존 로직 유지)
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (notiRef.current && !notiRef.current.contains(e.target)) {
        setIsNotiOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // 4. 알림 클릭 시 읽음 처리 및 이동
  const handleNotiClick = async (id, url) => {
    try {
      await api.patch(`/notifications/${id}/read`);
      setNotifications(prev => 
        prev.map(n => n.id === id ? { ...n, isRead: true } : n)
      );
      setIsNotiOpen(false);
      navigate(url);
    } catch (error) {
      console.error("읽음 처리 실패:", error);
      navigate(url); 
    }
  };

  // 5. 알림 삭제 처리
  const handleNotiDelete = async (e, id) => {
    e.stopPropagation();
    try {
      await api.delete(`/notifications/${id}`);
      setNotifications(prev => prev.filter(n => n.id !== id));
    } catch (error) {
      console.error("알림 삭제 실패:", error);
      alert("알림 삭제에 실패했습니다.");
    }
  };

  // 6. 로그아웃 처리
  const handleLogout = async () => {
    if (!window.confirm("로그아웃 하시겠습니까?")) return;
    try {
      await api.post('/auth/logout'); 
    } catch (error) {
      console.error("로그아웃 실패:", error);
    } finally {
      // 💡 로그아웃 시 SSE 연결 및 예약된 재연결 타이머도 명시적으로 종료
      clearScheduledReconnect();
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      setIsLoggedIn(false);
      setNotifications([]); 
      alert("로그아웃 되었습니다. 👋");
      navigate('/');
    }
  };

  const unreadCount = notifications.filter(n => !n.isRead).length;

  return (
    <header className="main-header">
      <div className="header-inner">
        <Link to="/" className="logo">MOIM MOIM</Link>
        <nav>
          <ul className="nav-menu">
            <li className="nav-item" onClick={() => navigate('/meetings')}>모임 찾기</li>
            <li className="nav-item">커뮤니티</li>
            {isLoggedIn && (
              <li className="nav-item" onClick={() => navigate('/mypage')}>내 모임</li>
            )}
          </ul>
        </nav>

        <div className="user-actions">
          {isLoggedIn ? (
            <>
              <div className="noti-container" ref={notiRef}>
                <button 
                  className={`btn-noti ${isNotiOpen ? 'active' : ''}`} 
                  onClick={() => setIsNotiOpen(!isNotiOpen)}
                >
                  <span className="noti-icon">🔔</span>
                  {unreadCount > 0 && <span className="noti-badge">{unreadCount}</span>}
                </button>

                {isNotiOpen && (
                  <div className="noti-dropdown">
                    <div className="noti-header">최근 알림</div>
                    <div className="noti-list">
                      {notifications.length > 0 ? (
                        notifications.map(n => (
                          <div 
                            key={n.id} 
                            className={`noti-item ${n.isRead ? '' : 'unread'}`}
                            onClick={() => handleNotiClick(n.id, n.url)}
                          >
                            <div className="noti-content-wrapper">
                              <p>{n.content}</p>
                              <span className="noti-time">
                                {n.createdAt?.split('T')[0]} 
                              </span>
                            </div>
                            <button 
                              className="btn-noti-delete"
                              onClick={(e) => handleNotiDelete(e, n.id)}
                            >
                              &times;
                            </button>
                          </div>
                        ))
                      ) : (
                        <div className="noti-empty">새로운 알림이 없습니다.</div>
                      )}
                    </div>
                    <div className="noti-footer" onClick={() => navigate('/mypage')}>
                      전체 보기
                    </div>
                  </div>
                )}
              </div>

              <button className="btn-mypage" onClick={() => navigate('/mypage')}>마이페이지</button>
              <button className="btn-create" onClick={() => navigate('/meeting-create')}>모임 만들기</button>
              <button className="btn-logout-text" onClick={handleLogout}>로그아웃</button>
            </>
          ) : (
            <>
              <button className="btn-login" onClick={() => navigate('/login')}>로그인</button>
              <button className="btn-create" onClick={() => navigate('/register')}>회원가입</button>
            </>
          )}
        </div>
      </div>
    </header>
  );
};

export default Header;