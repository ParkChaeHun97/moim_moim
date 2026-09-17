import React, { useState, useEffect } from 'react';
import api from '../../api/axios'; 
import { useNavigate, useSearchParams } from 'react-router-dom'; // 💡 useSearchParams 추가
import './MyPage.css';

const MyPage = () => {
    const [searchParams] = useSearchParams(); // 💡 URL의 쿼리 스트링을 읽기 위함
    const navigate = useNavigate();

    // 💡 초기값 설정: URL에 tab 파라미터가 있으면 그 값을, 없으면 'created'를 사용
    const initialTab = searchParams.get('tab') === 'applied' ? 'applied' : 'created';
    const [activeTab, setActiveTab] = useState(initialTab); 

    const [meetings, setMeetings] = useState([]);
    const [userInfo, setUserInfo] = useState(null);
    const [loading, setLoading] = useState(true);

    // 💡 모달 관련 상태
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [selectedPostId, setSelectedPostId] = useState(null);
    const [participants, setParticipants] = useState([]);
    const [modalLoading, setModalLoading] = useState(false);

    // 0. 💡 URL 파라미터 변경 감지 (알림 클릭 등으로 탭이 바뀔 때 대응)
    useEffect(() => {
        const tab = searchParams.get('tab');
        if (tab === 'created' || tab === 'applied') {
            setActiveTab(tab);
        }
    }, [searchParams]);

    // 1. 유저 정보 로드
    useEffect(() => {
        const fetchUserInfo = async () => {
            try {
                const response = await api.get('/members/me');
                setUserInfo(response.data);
            } catch (error) {
                console.error("유저 정보 로딩 실패:", error);
            }
        };
        fetchUserInfo();
    }, []);

    // 2. 탭 전환 시 데이터 로드
    useEffect(() => {
        fetchMyMeetings();
    }, [activeTab]);

    const fetchMyMeetings = async () => {
        setLoading(true);
        try {
            // 💡 엔드포인트는 기존 그대로 유지
            const endpoint = activeTab === 'created' ? '/mypage/meetings/created' : '/mypage/meetings/applied';
            const response = await api.get(endpoint);
            setMeetings(response.data || []);
        } catch (error) {
            console.error("데이터 로딩 실패:", error);
            setMeetings([]); 
        } finally {
            setLoading(false);
        }
    };

    // 3. 신청자 관리 모달 열기
    const openManageModal = async (postId) => {
        setSelectedPostId(postId);
        setIsModalOpen(true);
        setModalLoading(true);
        try {
            const response = await api.get(`/mypage/meeting/${postId}/participants`);
            setParticipants(response.data);
        } catch (error) {
            console.error("명단 로딩 실패:", error);
            alert("신청자 명단을 불러오지 못했습니다.");
        } finally {
            setModalLoading(false);
        }
    };

    // 4. 신청 승인/거절 처리
    const handleStatusUpdate = async (participationId, newStatus) => {
        const actionText = newStatus === 'ACCEPTED' ? '승인' : '거절';
        if (!window.confirm(`${actionText}하시겠습니까?`)) return;

        try {
            await api.patch(`/participation/${participationId}/status`, null, {
                params: { status: newStatus }
            });
            const response = await api.get(`/mypage/meeting/${selectedPostId}/participants`);
            setParticipants(response.data);
        } catch (error) {
            console.error("상태 변경 실패:", error);
            alert("처리에 실패했습니다.");
        }
    };

    const getStatusLabel = (status) => {
        switch (status) {
            case 'ACCEPTED': return '✅ 승인됨';
            case 'REJECTED': return '❌ 거절됨';
            case 'APPLIED': return '⏳ 신청완료';
            case 'WAITING': return '📋 대기중';
            case 'CANCELLED': return '🚫 취소함';
            default: return '⏳ 확인중';
        }
    };

    const handleCancelParticipation = async (participationId, e) => {
        e.stopPropagation();
        if (!window.confirm('신청을 취소하시겠습니까?')) return;

        try {
            await api.delete(`/participation/${participationId}`);
            setMeetings(prev => prev.map(m => 
                m.participationId === participationId 
                    ? { ...m, status: 'CANCELLED' } 
                    : m
            ));
        } catch (error) {
            console.error("취소 실패:", error);
            alert(error.response?.data?.message || "취소에 실패했습니다.");
        }
    };

    return (
        <div className="mypage-wrapper">
            <header className="profile-header-section">
                <div className="profile-avatar">
                    {userInfo?.nickname ? userInfo.nickname[0] : 'U'}
                </div>
                <h1>👋 안녕하세요, {userInfo?.nickname || '유저'}님!</h1>
                <p className="user-email">{userInfo?.email || '이메일 정보 없음'}</p>
                <button className="btn-edit-profile" onClick={() => navigate('/profile/edit')}>
                    프로필 수정
                </button>
            </header>

            <div className="mypage-tabs-container">
                <button 
                    className={`tab-btn ${activeTab === 'created' ? 'active' : ''}`}
                    onClick={() => setActiveTab('created')}
                >
                    내가 만든 모임
                </button>
                <button 
                    className={`tab-btn ${activeTab === 'applied' ? 'active' : ''}`}
                    onClick={() => setActiveTab('applied')}
                >
                    참여 신청 내역
                </button>
            </div>

            <section className="my-meeting-list-section">
                {loading ? (
                    <div className="loading">불러오는 중...</div>
                ) : meetings.length > 0 ? (
                    <div className="meeting-grid">
                        {meetings.map((meeting) => (
                            <div key={meeting.id} className="meeting-card my-card" onClick={() => navigate(`/meetings/${meeting.id}`)}>
                                <div className="card-content">
                                    <div className="card-category">{meeting.categoryName}</div>
                                    <h3 className="card-title">{meeting.title}</h3>
                                    <div className="card-footer-action">
                                        {activeTab === 'created' ? (
                                            <button 
                                                className="btn-card-manage"
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    openManageModal(meeting.id);
                                                }}
                                            >
                                                신청자 관리
                                            </button>
                                        ) : (
                                            <div className="applied-status-action">
                                                <span className={`status-badge ${meeting.status}`}>
                                                    {getStatusLabel(meeting.status)}
                                                </span>
                                                {(meeting.status === 'APPLIED' || meeting.status === 'ACCEPTED') && (
                                                    <button 
                                                        className="btn-cancel-participation"
                                                        onClick={(e) => handleCancelParticipation(meeting.participationId, e)}
                                                    >
                                                        취소
                                                    </button>
                                                )}
                                            </div>
                                        )}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                ) : (
                    <div className="empty-state">
                        <p>내역이 없습니다.</p>
                        <button className="btn-goto-home" onClick={() => navigate('/')}>모임 찾아보기 🔍</button>
                    </div>
                )}
            </section>

            {isModalOpen && (
                <div className="modal-overlay" onClick={() => setIsModalOpen(false)}>
                    <div className="modal-content" onClick={(e) => e.stopPropagation()}>
                        <div className="modal-header">
                            <h2>신청자 관리</h2>
                            <button className="btn-close" onClick={() => setIsModalOpen(false)}>&times;</button>
                        </div>
                        <div className="modal-body">
                            {modalLoading ? (
                                <p>로딩 중...</p>
                            ) : participants.length > 0 ? (
                                participants.map((p) => (
                                    <div key={p.participationId} className="participant-row">
                                        <div className="p-main-info">
                                            <div className="p-nick">{p.nickname}</div>
                                            <div className="p-reason">{p.joinReason || "참여 동기가 없습니다."}</div>
                                        </div>
                                        <div className="p-side-actions">
                                            {p.status === 'APPLIED' ? (
                                                <div className="mini-btn-group">
                                                    <button className="btn-mini-accept" onClick={() => handleStatusUpdate(p.participationId, 'ACCEPTED')}>승인</button>
                                                    <button className="btn-mini-reject" onClick={() => handleStatusUpdate(p.participationId, 'REJECTED')}>거절</button>
                                                </div>
                                            ) : (
                                                <span className={`status-text ${p.status}`}>{p.status}</span>
                                            )}
                                        </div>
                                    </div>
                                ))
                            ) : (
                                <p className="no-data">아직 신청자가 없습니다.</p>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default MyPage;